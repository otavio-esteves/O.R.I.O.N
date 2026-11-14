# Reflection entries are included only by the opt-in exit capture qualification scope.
-keep class network.orion.app.resources.OrionExitEvidenceCaptureBridge {
    public static java.lang.String validate(android.content.Context, java.lang.String, int, int, int);
    public static android.os.Bundle collect(android.content.Context, android.os.IBinder, java.lang.String, java.lang.String, int, int, int, android.os.CancellationSignal, android.os.CancellationSignal);
}
