package network.orion.ai.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.File
import network.orion.core.time.AndroidOrionClock

/** Test-APK-only synthetic stuck ownership; no model, native fault or production hook. */
class OrionWatchdogProbeService : Service() {
    private lateinit var watchdog: OrionNativeUnresponsiveWatchdog
    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == "$packageName:ai")
        watchdog = OrionNativeUnresponsiveWatchdog(AndroidOrionClock(contentResolver), 250,
            OrionNativeWatchdogScheduler()) {
            check(Application.getProcessName() == "$packageName:ai")
            File(cacheDir, MARKER).appendText("\nSELF_KILL")
            Process.killProcess(Process.myPid())
        }
        // Deliberately never quiesces. This substitutes ownership only, not real JNI behavior.
        watchdog.beginWork()
    }

    override fun onBind(intent: Intent?): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            check(Binder.getCallingUid() == Process.myUid())
            data.enforceInterface(DESCRIPTOR)
            checkNotNull(reply).writeNoException()
            reply.writeInt(Process.myPid())
            reply.writeString(Application.getProcessName())
            return true
        }
    }

    override fun onDestroy() {
        File(cacheDir, MARKER).writeText("DESTROYED")
        watchdog.armAll()
        watchdog.closeWhenQuiet()
        super.onDestroy()
    }

    companion object {
        const val MARKER = "orion_watchdog_test_lifecycle.txt"
        const val DESCRIPTOR = "network.orion.watchdog.probe"
    }
}
