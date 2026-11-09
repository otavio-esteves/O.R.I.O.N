package network.orion.ai.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionNativeWatchdogRuntimeTest {
    @Test fun unboundDestroyedAiProbeSelfTerminatesWhileInstrumentationMainSurvives() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val mainPid = Process.myPid()
        val marker = File(app.cacheDir, OrionWatchdogProbeService.MARKER)
        if (marker.exists()) assertTrue(marker.delete())
        val connected = CountDownLatch(1)
        val died = CountDownLatch(1)
        val remote = AtomicReference<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote.set(checkNotNull(binder)); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { }
        }
        var bound = app.bindService(Intent().setComponent(ComponentName(app, OrionWatchdogProbeService::class.java)),
            connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound)
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val service = checkNotNull(remote.get())
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(OrionWatchdogProbeService.DESCRIPTOR)
                assertTrue(service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                reply.readException()
                val aiPid = reply.readInt()
                assertTrue(aiPid > 0 && aiPid != mainPid)
                assertEquals("${app.packageName}:ai", reply.readString())
            } finally { data.recycle(); reply.recycle() }
            service.linkToDeath({ died.countDown() }, 0)
            app.unbindService(connection); bound = false
            assertTrue("Independent watchdog did not terminate destroyed busy probe", died.await(6, TimeUnit.SECONDS))
            assertEquals(mainPid, Process.myPid())
            assertEquals("DESTROYED\nSELF_KILL", marker.readText())
            assertFalse(service.isBinderAlive)
        } finally {
            if (bound) app.unbindService(connection)
            if (marker.exists()) assertTrue(marker.delete())
        }
    }
}
