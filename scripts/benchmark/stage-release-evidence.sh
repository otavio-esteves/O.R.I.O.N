#!/bin/sh
set -eu

apk=$1
bundle=$2
mapping=$3
symbols=$4
dependency_report=$5
output=$6

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Android SDK directory is required}}
llvm_readelf=$(find "$sdk/ndk/${ORION_NDK_VERSION:?NDK version is required}/toolchains/llvm/prebuilt" \
    -path '*/bin/llvm-readelf' -print -quit)
test -x "$llvm_readelf"
llvm_bin=$(dirname "$llvm_readelf")
mkdir -p "$output"
work=$(mktemp -d)
trap 'rm -- "$work/apk.so" "$work/bundle.so" "$work/symbols.dbg" "$work/proguard.map" 2>/dev/null || :; rmdir "$work"' EXIT
awk '/^# compiler: R8$/ { found = 1 } END { exit !found }' "$mapping"
unzip -p "$bundle" BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map > "$work/proguard.map"
cmp "$mapping" "$work/proguard.map"

build_id() {
    "$llvm_readelf" --notes "$1" | awk '/Build ID:/ { print $3 }'
}

check_native_symbols() {
    library=$1
    entry_point=$2
    source=$3
    abi=arm64-v8a
    unzip -p "$apk" "lib/$abi/$library" > "$work/apk.so"
    unzip -p "$bundle" "base/lib/$abi/$library" > "$work/bundle.so"
    unzip -p "$symbols" "$abi/$library.dbg" > "$work/symbols.dbg"
    id=$(build_id "$work/apk.so")
    test -n "$id"
    test "$id" = "$(build_id "$work/bundle.so")"
    test "$id" = "$(build_id "$work/symbols.dbg")"
    address=$("$llvm_bin/llvm-nm" --defined-only "$work/apk.so" --dynamic |
        awk -v entry="$entry_point" '$NF == entry { print "0x" $1 }')
    test -n "$address"
    frame=$("$llvm_bin/llvm-symbolizer" --obj="$work/symbols.dbg" "$address")
    printf '%s\n' "$frame" | awk -v source="$source" '
        index($0, source ":") { n = split($0, parts, ":"); if (parts[n-1] + 0 > 0) found = 1 }
        END { exit !found }
    '
    printf '%s\t%s\t%s\n' "$library" "$id" "$source"
}

{
    printf 'library\tbuildId\tsymbolicatedSource\n'
    check_native_symbols liborion_llama.so Java_network_orion_ai_llama_OrionLlamaRuntime_nativeCreate orion_llama_jni.cpp
    check_native_symbols liborion_whisper.so Java_network_orion_voice_service_OrionWhisper_transcribe orion_whisper_jni.cpp
} > "$output/native-symbolication.tsv"

{
    printf 'dependency\tarchiveRevision\tarchiveSha256\tlicense\n'
    for component in llama whisper
    do
        case "$component" in
            llama) cmake=ai/llama/src/main/cpp/CMakeLists.txt ;;
            whisper) cmake=voice/service/src/main/cpp/CMakeLists.txt ;;
        esac
        revision=$(awk -F/ '/URL https:\/\/codeload\.github\.com\// {print $NF}' "$cmake")
        archive_sha=$(awk -F= '/URL_HASH SHA256=/ {print $NF}' "$cmake")
        test "${#revision}" -eq 40
        test "${#archive_sha}" -eq 64
        license="$component.cpp-MIT.txt"
        unzip -p "$apk" "assets/orion_licenses/$license" > "$output/$license"
        test -s "$output/$license"
        printf '%s.cpp\t%s\t%s\t%s\n' "$component" "$revision" "$archive_sha" "$license"
    done
} > "$output/native-dependencies.tsv"

cp "$apk" "$bundle" "$mapping" "$symbols" "$dependency_report" "$output/"
(
    cd "$output"
    sha256sum app-release-unsigned.apk app-release.aab mapping.txt native-debug-symbols.zip \
        release-dependencies.tsv native-dependencies.tsv native-symbolication.tsv llama.cpp-MIT.txt whisper.cpp-MIT.txt \
        > SHA256SUMS
)
