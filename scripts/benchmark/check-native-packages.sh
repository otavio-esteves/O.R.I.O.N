#!/bin/sh
set -eu

: "${ANDROID_HOME:?Android SDK is required}"
: "${ORION_NDK_VERSION:?Pinned NDK version is required}"
readelf_bin="$ANDROID_HOME/ndk/$ORION_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
zipalign_bin="$ANDROID_HOME/build-tools/36.0.0/zipalign"
test -x "$readelf_bin"
test -x "$zipalign_bin"
qualification=false
if test "${1:-}" = --qualification; then qualification=true; shift; fi
test "$#" -gt 0
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM

for package in "$@"; do
    test -f "$package"
    if test "$qualification" = true; then
        case "$package" in *.apk) : ;; *) echo 'Qualification profile requires an APK' >&2; exit 1 ;; esac
        "$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$package" > "$scratch/badging"
        perl -ne 'BEGIN { $found=0; $debug=0; }
            $found=1 if /^package: name=\x27network\.orion\.qualification\x27/;
            $debug=1 if /^application-debuggable/;
            END { exit(! $found || $debug); }' "$scratch/badging"
    fi
    unzip -Z1 "$package" | awk '/(^|\/)lib\/[^/]+\/[^/]+\.so$/' > "$scratch/entries"
    test -s "$scratch/entries"
    case "$package" in
        *.apk) "$zipalign_bin" -c -P 16 4 "$package" ;;
        *.aab) : ;;
        *) echo "Unsupported Android artifact: $package" >&2; exit 1 ;;
    esac
    while IFS= read -r entry; do
        case "$entry" in
            lib/arm64-v8a/*.so|base/lib/arm64-v8a/*.so) machine='AArch64' ;;
            lib/x86_64/*.so)
                if test "$qualification" != true; then
                    case "$package" in */debug/*) : ;; *) echo 'Unexpected release ABI' >&2; exit 1 ;; esac
                fi
                machine='Advanced Micro Devices X86-64'
                ;;
            *) echo "Unexpected library path: $entry" >&2; exit 1 ;;
        esac
        unzip -p "$package" "$entry" > "$scratch/library.so"
        "$readelf_bin" -lW "$scratch/library.so" > "$scratch/program-headers"
        perl -ane 'if ($F[0] eq "LOAD") { $count++; die "ELF LOAD alignment below 16 KiB\n" if hex($F[-1]) < 16384; } END { die "ELF has no LOAD segment\n" unless $count; }' "$scratch/program-headers"
        "$readelf_bin" -h "$scratch/library.so" > "$scratch/elf-header"
        grep -E "Machine:[[:space:]]+$machine$" "$scratch/elf-header" > /dev/null
        echo "$package!$entry: ELF/ABI PASS"
    done < "$scratch/entries"
    echo "$package: static native packaging PASS"
done
