#!/bin/sh
set -eu

gate=docs/compatibility/native-compatibility-gate.json
perl scripts/benchmark/validate-native-gate.pl "$gate" .

test_dir=$(mktemp -d)
package_staging=$(mktemp -d)
trap 'rm -rf "$test_dir" "$package_staging"' EXIT HUP INT TERM
fixture_gate="$test_dir/not-applicable.json"
perl -MJSON::PP -e '
    local $/; open my $in, "<", $ARGV[0] or die $!;
    my $gate = decode_json(<$in>);
    $gate->{status} = "NOT_APPLICABLE_NO_NATIVE_ARTIFACTS";
    $_ = "NOT_RUN" for values %{$gate->{checkResults}};
    print encode_json($gate);
' "$gate" > "$fixture_gate"
mkdir -p "$test_dir/src/main/cpp"
touch "$test_dir/src/main/cpp/liborion.so"

if perl scripts/benchmark/validate-native-gate.pl "$fixture_gate" "$test_dir" 2>/dev/null; then
    echo 'native artifact retained NOT_APPLICABLE_NO_NATIVE_ARTIFACTS' >&2
    exit 1
fi

packaged_root=$(mktemp -d)
trap 'rm -rf "$test_dir" "$package_staging" "$packaged_root"' EXIT HUP INT TERM
mkdir -p "$package_staging/lib/arm64-v8a" "$packaged_root/app/build/outputs/apk/debug"
touch "$package_staging/lib/arm64-v8a/libtransitive.so"
(cd "$package_staging" && jar --create --file "$packaged_root/app/build/outputs/apk/debug/app-debug.apk" lib)

if perl scripts/benchmark/validate-native-gate.pl "$fixture_gate" "$packaged_root" 2>/dev/null; then
    echo 'packaged transitive native artifact retained NOT_APPLICABLE_NO_NATIVE_ARTIFACTS' >&2
    exit 1
fi

pending_gate="$test_dir/pending.json"
perl -MJSON::PP -e '
    local $/; open my $in, "<", $ARGV[0] or die $!;
    my $gate = decode_json(<$in>); $gate->{status} = "PENDING";
    print encode_json($gate);
' "$fixture_gate" > "$pending_gate"
perl scripts/benchmark/validate-native-gate.pl "$pending_gate" "$test_dir"
invalid_pass="$test_dir/invalid-pass.json"
perl -MJSON::PP -e '
    local $/; open my $in, "<", $ARGV[0] or die $!;
    my $gate = decode_json(<$in>); $gate->{status} = "PASS";
    print encode_json($gate);
' "$pending_gate" > "$invalid_pass"
if perl scripts/benchmark/validate-native-gate.pl "$invalid_pass" "$test_dir" 2>/dev/null; then
    echo 'incomplete native gate incorrectly passed' >&2
    exit 1
fi

echo 'native compatibility gate tests passed'
