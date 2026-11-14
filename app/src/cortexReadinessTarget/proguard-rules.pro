# Only the explicit read-only qualification scope includes this reflection bridge.
-keep class network.orion.app.ai.OrionCortexReadinessBridge {
    public static android.os.Bundle collect(android.content.Context, java.lang.String, int);
}
