# Framework-only entry exists solely in the explicitly selected runtime capture qualification artifact.
-keep class network.orion.app.ai.OrionCortexRuntimeCaptureBridge {
    public static android.os.Bundle run(android.app.Instrumentation);
}
