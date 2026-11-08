#!/bin/sh
set -eu
: "${ANDROID_HOME:?Android SDK is required}"
: "${ORION_NDK_VERSION:?Pinned NDK is required}"
toolchain="$ANDROID_HOME/ndk/$ORION_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
zipalign_bin="$ANDROID_HOME/build-tools/36.0.0/zipalign"
checker="$(pwd)/scripts/benchmark/check-native-packages.sh"
fixtures=$(mktemp -d)
trap 'rm -rf "$fixtures"' EXIT HUP INT TERM
mkdir -p "$fixtures/staging/lib/arm64-v8a" "$fixtures/debug" "$fixtures/release"
cat > "$fixtures/probe.c" <<'SOURCE'
int orion_fixture(void) { return 0; }
SOURCE
build_fixture() {
    "$toolchain/aarch64-linux-android31-clang" -shared -nostdlib -Wl,-z,max-page-size="$1" \
        "$fixtures/probe.c" -o "$fixtures/staging/lib/arm64-v8a/liborion_fixture.so"
    jar --create --no-compress --file "$fixtures/raw.apk" -C "$fixtures/staging" lib
    "$zipalign_bin" -f -P 16 4 "$fixtures/raw.apk" "$fixtures/release/fixture.apk"
}
build_fixture 16384
sh "$checker" "$fixtures/release/fixture.apk"
if sh "$checker" --qualification "$fixtures/release/fixture.apk" >/dev/null 2>&1; then
    echo 'Unidentified APK incorrectly accepted as qualification' >&2
    exit 1
fi
if sh "$checker" "$fixtures/raw.apk" >/dev/null 2>&1; then
    echo 'unaligned APK incorrectly accepted' >&2
    exit 1
fi
build_fixture 4096
if sh "$checker" "$fixtures/release/fixture.apk" >/dev/null 2>&1; then
    echo '4 KiB ELF incorrectly accepted' >&2
    exit 1
fi
build_fixture 16384
mkdir -p "$fixtures/staging/lib/x86_64"
mv "$fixtures/staging/lib/arm64-v8a/liborion_fixture.so" "$fixtures/staging/lib/x86_64/"
jar --create --no-compress --file "$fixtures/wrong-abi.apk" -C "$fixtures/staging" lib
"$zipalign_bin" -f -P 16 4 "$fixtures/wrong-abi.apk" "$fixtures/debug/fixture.apk"
if sh "$checker" "$fixtures/debug/fixture.apk" >/dev/null 2>&1; then
    echo 'mislabeled ABI incorrectly accepted' >&2
    exit 1
fi
cp "$fixtures/debug/fixture.apk" "$fixtures/release/fixture.apk"
if sh "$checker" "$fixtures/release/fixture.apk" >/dev/null 2>&1; then
    echo 'unexpected release ABI incorrectly accepted' >&2
    exit 1
fi
echo 'native package artifact negative checks passed'
