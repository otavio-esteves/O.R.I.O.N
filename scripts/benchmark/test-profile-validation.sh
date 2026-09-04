#!/bin/sh
set -eu

set -- benchmark/testdata/valid-*.json
perl scripts/benchmark/validate-profiles.pl "$@"

set -- benchmark/testdata/invalid-*.json
perl scripts/benchmark/validate-profiles.pl --expect-invalid "$@"

echo 'benchmark profile schema tests passed'
