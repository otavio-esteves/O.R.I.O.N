#!/usr/bin/env perl
use strict;
use warnings;
use File::Find qw(find);
use JSON::PP qw(decode_json);

my $file = shift @ARGV or die "usage: $0 GATE.json [REPOSITORY_ROOT]\n";
my $repository_root = shift @ARGV // '.';
die "usage: $0 GATE.json [REPOSITORY_ROOT]\n" if @ARGV;

open my $handle, '<', $file or die "$file: cannot open: $!\n";
local $/;
my $gate = eval { decode_json(<$handle>) };
die "$file: invalid JSON: $@" if $@;
die "$file: root must be an object\n" unless ref($gate) eq 'HASH';

my @expected_keys = qw(schemaVersion gate status reason activationIssue requiredChecksWhenApplicable checkResults);
my %expected = map { $_ => 1 } @expected_keys;
die "$file: unsupported property $_\n" for grep { !$expected{$_} } keys %$gate;
die "$file: schemaVersion must equal 2\n"
    unless defined($gate->{schemaVersion}) && $gate->{schemaVersion} == 2;
die "$file: gate must equal NATIVE_COMPATIBILITY_GATE\n"
    unless ($gate->{gate} // '') eq 'NATIVE_COMPATIBILITY_GATE';
die "$file: reason must be present\n"
    unless defined($gate->{reason}) && !ref($gate->{reason}) && $gate->{reason} ne '';
die "$file: activationIssue must be present\n"
    unless defined($gate->{activationIssue}) && !ref($gate->{activationIssue}) && $gate->{activationIssue} ne '';
die "$file: requiredChecksWhenApplicable must be an array\n"
    unless ref($gate->{requiredChecksWhenApplicable}) eq 'ARRAY';
die "$file: checkResults must be an object\n"
    unless ref($gate->{checkResults}) eq 'HASH';

my @required_checks = qw(
    ELF_16_KIB_ALIGNMENT
    APK_AAB_PACKAGING
    LOAD_TEST
    ABI_COMPATIBILITY
);
my %declared_checks = map { $_ => 1 } @{$gate->{requiredChecksWhenApplicable}};
my %required_checks = map { $_ => 1 } @required_checks;
for my $required (@required_checks) {
    die "$file: missing required check $required\n" unless $declared_checks{$required};
    die "$file: missing result for $required\n" unless exists $gate->{checkResults}{$required};
}
die "$file: unsupported required check $_\n"
    for grep { !$required_checks{$_} } keys %declared_checks;
die "$file: unsupported check result $_\n"
    for grep { !$required_checks{$_} } keys %{$gate->{checkResults}};
for my $check (@required_checks) {
    my $result = $gate->{checkResults}{$check} // '';
    die "$file: invalid result for $check\n"
        unless $result eq 'PASS' || $result eq 'FAIL' || $result eq 'NOT_RUN';
}

my @native_artifacts;
find(
    {
        no_chdir => 1,
        wanted => sub {
            $File::Find::prune = 0;
            if (-d $_ && $_ ne $repository_root && $_ =~ m{(?:^|/)(?:\.git|\.gradle|\.idea|build)(?:/|$)}) {
                $File::Find::prune = 1;
                return;
            }
            return unless -f $_;
            if ($_ =~ /(?:\.so|\.a|\.c|\.cc|\.cpp|\.cxx|\.h|\.hh|\.hpp)$/
                    || $_ =~ m{/(?:CMakeLists\.txt|Android\.mk|Application\.mk)$}) {
                push @native_artifacts, $_;
                return;
            }
            return unless $_ =~ /(?:\.gradle|\.gradle\.kts|\.kt|\.java)$/;
            open my $source, '<', $_ or die "$_: cannot inspect for native markers: $!\n";
            while (my $line = <$source>) {
                if ($line =~ /(?:externalNativeBuild|ndkBuild|System\.load(?:Library)?|\bexternal\s+fun\b)/) {
                    push @native_artifacts, $_;
                    last;
                }
            }
        },
    },
    $repository_root,
);

# Build outputs are excluded from the source scan, but APK/AAB contents must be
# inspected so a transitive dependency cannot introduce a hidden native library.
my @android_packages;
find(
    {
        no_chdir => 1,
        wanted => sub {
            $File::Find::prune = 0;
            return unless -f $_;
            push @android_packages, $_
                if $_ =~ m{/build/outputs/(?:apk|bundle)/.*\.(?:apk|aab)$};
        },
    },
    $repository_root,
);
for my $package (@android_packages) {
    open my $entries, '-|', 'unzip', '-Z1', $package
        or die "$package: cannot inspect Android package: $!\n";
    local $/ = "\n";
    while (my $entry = <$entries>) {
        chomp $entry;
        push @native_artifacts, "$package!$entry"
            if $entry =~ m{(?:^|/)lib/[^/]+/[^/]+\.so$};
    }
    close $entries or die "$package: package inspection failed\n";
}

my $status = $gate->{status} // '';
die "$file: invalid status\n"
    unless $status eq 'NOT_APPLICABLE_NO_NATIVE_ARTIFACTS'
        || $status eq 'PENDING'
        || $status eq 'PASS'
        || $status eq 'FAIL';

if (@native_artifacts && $status eq 'NOT_APPLICABLE_NO_NATIVE_ARTIFACTS') {
    die "$file: native artifact detected while gate is not applicable: $native_artifacts[0]\n";
}
if (!@native_artifacts && $status ne 'NOT_APPLICABLE_NO_NATIVE_ARTIFACTS') {
    die "$file: gate status $status requires a native artifact\n";
}

my @results = map { $gate->{checkResults}{$_} } @required_checks;
if ($status eq 'NOT_APPLICABLE_NO_NATIVE_ARTIFACTS') {
    die "$file: not-applicable gate requires all checks to be NOT_RUN\n"
        if grep { $_ ne 'NOT_RUN' } @results;
} elsif ($status eq 'PENDING') {
    die "$file: pending gate requires at least one NOT_RUN check\n"
        unless grep { $_ eq 'NOT_RUN' } @results;
} elsif ($status eq 'PASS') {
    die "$file: passing gate requires every check to pass\n"
        if grep { $_ ne 'PASS' } @results;
} elsif ($status eq 'FAIL') {
    die "$file: failing gate requires at least one failed check\n"
        unless grep { $_ eq 'FAIL' } @results;
}

print "$file: valid ($status; " . scalar(@native_artifacts) . " native artifacts)\n";
