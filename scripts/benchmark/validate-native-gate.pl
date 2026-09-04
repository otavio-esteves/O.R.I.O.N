#!/usr/bin/env perl
use strict;
use warnings;
use JSON::PP qw(decode_json);

my $file = shift @ARGV or die "usage: $0 GATE.json\n";
die "usage: $0 GATE.json\n" if @ARGV;
open my $handle, '<', $file or die "$file: cannot open: $!\n";
local $/;
my $gate = eval { decode_json(<$handle>) };
die "$file: invalid JSON: $@" if $@;
die "$file: root must be an object\n" unless ref($gate) eq 'HASH';

my @expected_keys = qw(schemaVersion gate status reason requiredChecksWhenApplicable);
my %expected = map { $_ => 1 } @expected_keys;
die "$file: unsupported property $_\n" for grep { !$expected{$_} } keys %$gate;
die "$file: schemaVersion must equal 1\n"
    unless defined($gate->{schemaVersion}) && $gate->{schemaVersion} == 1;
die "$file: gate must equal NATIVE_COMPATIBILITY_GATE\n"
    unless ($gate->{gate} // '') eq 'NATIVE_COMPATIBILITY_GATE';
die "$file: initial status must equal NOT_APPLICABLE_NO_NATIVE_ARTIFACTS\n"
    unless ($gate->{status} // '') eq 'NOT_APPLICABLE_NO_NATIVE_ARTIFACTS';
die "$file: reason must be present\n"
    unless defined($gate->{reason}) && !ref($gate->{reason}) && $gate->{reason} ne '';
die "$file: requiredChecksWhenApplicable must be an array\n"
    unless ref($gate->{requiredChecksWhenApplicable}) eq 'ARRAY';

my %checks = map { $_ => 1 } @{$gate->{requiredChecksWhenApplicable}};
for my $required (qw(ELF_16_KIB_ALIGNMENT APK_PACKAGING_ALIGNMENT LOAD_ON_COMPATIBLE_DEVICE_OR_EMULATOR)) {
    die "$file: missing required check $required\n" unless $checks{$required};
}

print "$file: valid\n";
