#!/usr/bin/env perl
use strict;
use warnings;
use JSON::PP qw(decode_json);
use Scalar::Util qw(looks_like_number);

my $expect_invalid = @ARGV && $ARGV[0] eq '--expect-invalid' ? shift @ARGV : 0;
die "usage: $0 [--expect-invalid] PROFILE.json [...]\n" unless @ARGV;

open my $schema_handle, '<', 'benchmark/schema/benchmark-profile.schema.json'
    or die "cannot open benchmark profile schema: $!\n";
local $/;
eval { decode_json(<$schema_handle>) };
die "benchmark profile schema is invalid JSON: $@" if $@;

sub validate_profile {
    my ($file) = @_;
    open my $handle, '<', $file or return ("cannot open: $!");
    local $/;
    my $decoded = eval { decode_json(<$handle>) };
    return ("invalid JSON: $@") if $@;
    my @errors;

    sub object {
        my ($value, $path, $errors) = @_;
        push @$errors, "$path must be an object" unless ref($value) eq 'HASH';
        return ref($value) eq 'HASH' ? $value : {};
    }
    sub string_value {
        my ($parent, $key, $path, $errors) = @_;
        my $value = $parent->{$key};
        push @$errors, "$path must be a non-empty string"
            if !defined($value) || ref($value) || $value eq '';
        return defined($value) && !ref($value) ? $value : '';
    }
    sub number_value {
        my ($parent, $key, $path, $minimum, $integer, $errors) = @_;
        my $value = $parent->{$key};
        my $valid = defined($value) && !ref($value) && looks_like_number($value)
            && $value >= $minimum && (!$integer || int($value) == $value);
        push @$errors, "$path must be " . ($integer ? 'an integer' : 'a number') . " >= $minimum"
            unless $valid;
        return $valid ? 0 + $value : 0;
    }
    sub exact_keys {
        my ($object, $path, $allowed, $errors) = @_;
        my %allowed = map { $_ => 1 } @$allowed;
        push @$errors, "$path contains unsupported property $_"
            for grep { !$allowed{$_} } keys %$object;
    }

    my $root = object($decoded, 'root', \@errors);
    my @root_keys = qw(schemaVersion profileId toolchainProfileId recordedAt environment device app workload measurements termination);
    exact_keys($root, 'root', \@root_keys, \@errors);
    push @errors, 'schemaVersion must equal 1'
        unless defined($root->{schemaVersion}) && !ref($root->{schemaVersion}) && $root->{schemaVersion} == 1;
    string_value($root, 'profileId', 'profileId', \@errors);
    string_value($root, 'toolchainProfileId', 'toolchainProfileId', \@errors);
    my $recorded_at = string_value($root, 'recordedAt', 'recordedAt', \@errors);
    push @errors, 'recordedAt must be a UTC timestamp'
        if $recorded_at ne '' && $recorded_at !~ /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;

    my $environment = object($root->{environment}, 'environment', \@errors);
    exact_keys($environment, 'environment', [qw(kind androidRuntimeEvidence)], \@errors);
    my $environment_kind = string_value($environment, 'kind', 'environment.kind', \@errors);
    push @errors, 'environment.kind is invalid'
        unless grep { $environment_kind eq $_ } qw(CONTAINER HOST ANDROID_EMULATOR PHYSICAL_DEVICE);
    my $runtime_evidence = string_value($environment, 'androidRuntimeEvidence', 'environment.androidRuntimeEvidence', \@errors);
    push @errors, 'environment.androidRuntimeEvidence is invalid'
        unless $runtime_evidence eq 'DEFINITIVE' || $runtime_evidence eq 'NON_DEFINITIVE';
    push @errors, 'DEFINITIVE Android runtime evidence requires PHYSICAL_DEVICE'
        if $runtime_evidence eq 'DEFINITIVE' && $environment_kind ne 'PHYSICAL_DEVICE';

    my $device = object($root->{device}, 'device', \@errors);
    my @device_keys = qw(manufacturer model soc ramMiB androidBuild apiLevel);
    exact_keys($device, 'device', \@device_keys, \@errors);
    string_value($device, $_, "device.$_", \@errors) for qw(manufacturer model soc androidBuild);
    number_value($device, 'ramMiB', 'device.ramMiB', 1, 1, \@errors);
    number_value($device, 'apiLevel', 'device.apiLevel', 1, 1, \@errors);

    my $app = object($root->{app}, 'app', \@errors);
    exact_keys($app, 'app', [qw(buildId commitSha buildVariant)], \@errors);
    string_value($app, 'buildId', 'app.buildId', \@errors);
    my $commit_sha = string_value($app, 'commitSha', 'app.commitSha', \@errors);
    push @errors, 'app.commitSha must contain 40 to 64 lowercase hexadecimal characters'
        if $commit_sha ne '' && $commit_sha !~ /^[0-9a-f]{40,64}$/;
    string_value($app, 'buildVariant', 'app.buildVariant', \@errors);

    my $workload = object($root->{workload}, 'workload', \@errors);
    my @workload_keys = qw(kind candidate artifactSha256 quantization inferenceBackend threads contextTokens generatedTokens);
    exact_keys($workload, 'workload', \@workload_keys, \@errors);
    my $kind = string_value($workload, 'kind', 'workload.kind', \@errors);
    push @errors, 'workload.kind must be LLM or STT' unless $kind eq 'LLM' || $kind eq 'STT';
    string_value($workload, 'candidate', 'workload.candidate', \@errors);
    my $hash = string_value($workload, 'artifactSha256', 'workload.artifactSha256', \@errors);
    push @errors, 'workload.artifactSha256 must contain 64 lowercase hexadecimal characters'
        if $hash ne '' && $hash !~ /^[0-9a-f]{64}$/;
    string_value($workload, 'quantization', 'workload.quantization', \@errors);
    string_value($workload, 'inferenceBackend', 'workload.inferenceBackend', \@errors);
    number_value($workload, 'threads', 'workload.threads', 1, 1, \@errors);
    number_value($workload, 'contextTokens', 'workload.contextTokens', 0, 1, \@errors);
    number_value($workload, 'generatedTokens', 'workload.generatedTokens', 0, 1, \@errors);

    my $measurements = object($root->{measurements}, 'measurements', \@errors);
    my @measurement_keys = qw(commandDurationMs loadTimeMs firstTokenLatencyMs tokensPerSecond rssBaselineKiB peakRssKiB rssAfterUnloadKiB batteryStartPercent batteryEndPercent initialTemperatureC thermalStatus);
    exact_keys($measurements, 'measurements', \@measurement_keys, \@errors);
    number_value($measurements, $_, "measurements.$_", 0, 1, \@errors)
        for qw(commandDurationMs loadTimeMs firstTokenLatencyMs rssBaselineKiB peakRssKiB rssAfterUnloadKiB);
    number_value($measurements, 'tokensPerSecond', 'measurements.tokensPerSecond', 0, 0, \@errors);
    my $battery_start = number_value($measurements, 'batteryStartPercent', 'measurements.batteryStartPercent', 0, 0, \@errors);
    my $battery_end = number_value($measurements, 'batteryEndPercent', 'measurements.batteryEndPercent', 0, 0, \@errors);
    push @errors, 'battery percentages must be <= 100' if $battery_start > 100 || $battery_end > 100;
    number_value($measurements, 'initialTemperatureC', 'measurements.initialTemperatureC', 0, 0, \@errors);
    string_value($measurements, 'thermalStatus', 'measurements.thermalStatus', \@errors);

    my $termination = object($root->{termination}, 'termination', \@errors);
    exact_keys($termination, 'termination', [qw(exitReason result)], \@errors);
    string_value($termination, 'exitReason', 'termination.exitReason', \@errors);
    my $result = string_value($termination, 'result', 'termination.result', \@errors);
    push @errors, 'termination.result must be PASS, FAIL, or INCONCLUSIVE'
        unless $result eq 'PASS' || $result eq 'FAIL' || $result eq 'INCONCLUSIVE';
    return @errors;
}

my $failed = 0;
for my $file (@ARGV) {
    my @errors = validate_profile($file);
    if ($expect_invalid) {
        if (@errors) { print "$file: rejected as expected\n"; }
        else { warn "$file: invalid fixture was accepted\n"; $failed = 1; }
    } elsif (@errors) {
        warn "$file: $_\n" for @errors;
        $failed = 1;
    } else {
        print "$file: valid\n";
    }
}
exit $failed;
