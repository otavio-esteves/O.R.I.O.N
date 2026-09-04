#!/usr/bin/env perl
use strict;
use warnings;
use JSON::PP qw(decode_json);
use JSON::Validator;

my $expect_invalid = @ARGV && $ARGV[0] eq '--expect-invalid' ? shift @ARGV : 0;
die "usage: $0 [--expect-invalid] PROFILE.json [...]\n" unless @ARGV;

my $schema_file = 'benchmark/schema/benchmark-profile.schema.json';
open my $schema_handle, '<', $schema_file
    or die "cannot open benchmark profile schema: $!\n";
local $/;
my $schema = eval { decode_json(<$schema_handle>) };
die "benchmark profile schema is invalid JSON: $@" if $@;

my $validator = JSON::Validator->new;
$validator->schema($schema);

sub validate_profile {
    my ($file) = @_;
    open my $handle, '<', $file or return ("cannot open: $!");
    local $/;
    my $profile = eval { decode_json(<$handle>) };
    return ("invalid JSON: $@") if $@;

    return map { $_->path . ': ' . $_->message } $validator->validate($profile);
}

my $failed = 0;
for my $file (@ARGV) {
    my @errors = validate_profile($file);
    if ($expect_invalid) {
        if (@errors) {
            print "$file: rejected as expected\n";
        } else {
            warn "$file: invalid fixture was accepted\n";
            $failed = 1;
        }
    } elsif (@errors) {
        warn "$file: $_\n" for @errors;
        $failed = 1;
    } else {
        print "$file: valid\n";
    }
}
exit $failed;
