#!/bin/sh
set -eu
: "${ANDROID_HOME:?Android SDK is required}"
scratch=$(mktemp -d)
trap 'rm -r -- "$scratch"' EXIT HUP INT TERM
for mode in valid renamed wrong_signature nonnative static_callback static_native; do
    stage="$scratch/$mode"
    mkdir -p "$stage/source" "$stage/classes" "$stage/dex"
    cat > "$stage/source/OrionLlamaEngine.java" <<'JAVA'
package network.orion.ai.llama;
public class OrionLlamaEngine {
    private native long nativeLoad(int fd, int context, int threads, OrionNativeCallbacks callback);
    private native void nativeInfer(long handle, byte[] prompt, int tokens, float temperature, OrionNativeCallbacks callback);
    private native void nativeDestroy(long id);
}
JAVA
    cat > "$stage/source/OrionNativeCallbacks.java" <<'JAVA'
package network.orion.ai.llama;
public class OrionNativeCallbacks {
    public boolean shouldCancel() { return false; }
    public boolean emit(byte[] data) { return true; }
}
JAVA
    case "$mode" in
        renamed) sed -i 's/emit(/renamed(/' "$stage/source/OrionNativeCallbacks.java" ;;
        wrong_signature) sed -i 's/nativeDestroy(long id)/nativeDestroy(int id)/' "$stage/source/OrionLlamaEngine.java" ;;
        nonnative) sed -i 's/private native void nativeDestroy(long id);/private void nativeDestroy(long id) {}/' "$stage/source/OrionLlamaEngine.java" ;;
        static_callback) sed -i 's/public boolean/public static boolean/g' "$stage/source/OrionNativeCallbacks.java" ;;
        static_native) sed -i 's/private native/private static native/g' "$stage/source/OrionLlamaEngine.java" ;;
    esac
    javac -d "$stage/classes" "$stage/source/OrionLlamaEngine.java" "$stage/source/OrionNativeCallbacks.java"
    "$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 31 \
        --lib "$ANDROID_HOME/platforms/android-37.0/android.jar" --output "$stage/dex" \
        "$stage/classes/network/orion/ai/llama/OrionLlamaEngine.class" \
        "$stage/classes/network/orion/ai/llama/OrionNativeCallbacks.class"
    jar --create --file "$stage/fixture.apk" -C "$stage/dex" classes.dex
    if test "$mode" = valid; then
        sh scripts/benchmark/verify-jni-contract.sh "$stage/fixture.apk"
    elif sh scripts/benchmark/verify-jni-contract.sh "$stage/fixture.apk" > "$stage/result" 2>&1; then
        echo "Invalid JNI fixture accepted: $mode" >&2
        exit 1
    fi
done
echo 'DEX JNI positive/renamed/signature/native-access/static fixtures: PASS'
