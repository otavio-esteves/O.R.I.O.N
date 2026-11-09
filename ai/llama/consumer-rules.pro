# Explicit JNI names and callback method are consumed by the native boundary.
-keep class network.orion.ai.llama.OrionLlamaRuntime { *; }
-keep interface network.orion.ai.llama.OrionLlamaPieceCallback { *; }
-keepclassmembers class * implements network.orion.ai.llama.OrionLlamaPieceCallback {
    public boolean onPiece(byte[]);
}
