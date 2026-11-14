# The opt-in measurement APK alone exposes these framework-typed reflection entries.
# This file is never part of normal Debug/Release or the ordinary R8 probe scope.
-keep class network.orion.app.resources.OrionPhysicalBaselineBridge {
    public static void validate(android.content.Context, int, int);
    public static android.os.Bundle collect(android.content.Context, android.os.IBinder, int, int, android.os.ResultReceiver);
}
