# Only this explicit qualification mode exposes the framework-typed reflection entry.
-keep class network.orion.app.ai.OrionCortexSingleAttemptBridge {
    public static android.os.Bundle prepare(android.content.Context);
    public static android.os.Bundle execute(android.content.Context);
    public static android.os.Bundle observe(android.content.Context);
    public static android.os.Bundle release(android.content.Context);
}
