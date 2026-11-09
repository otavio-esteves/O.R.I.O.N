#!/bin/sh
set -eu
# Test-only 260K-parameter model from the upstream project's fixture collection.
# No production profile or model is supplied by this artifact.
test "$#" -eq 1
output=$1
sha=270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d
url=https://huggingface.co/ggml-org/models/resolve/499bc8821c6b12b4e53c5bffcb21ec206f212d81/tinyllamas/stories260K.gguf
if test -f "$output" && printf '%s  %s\n' "$sha" "$output" | sha256sum --check --status; then exit 0; fi
mkdir -p "$(dirname "$output")"
temporary=$(mktemp "${output}.XXXXXX")
trap 'if test -f "$temporary"; then rm -- "$temporary"; fi' EXIT HUP INT TERM
curl --fail --location --retry 2 --connect-timeout 10 --max-time 90 --max-filesize 2097152 "$url" --output "$temporary"
printf '%s  %s\n' "$sha" "$temporary" | sha256sum --check -
mv -- "$temporary" "$output"
