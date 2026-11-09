package network.orion.ai.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import network.orion.ai.api.*
import network.orion.ai.cortex.OrionCortexClient
import network.orion.core.time.AndroidOrionClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionCortexClientBoundaryTest {
    @Test fun unavailableQualificationNeverBindsOrStartsNativeRuntime() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        var binds = 0
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                binds++; throw AssertionError("Unqualified Cortex cannot bind.")
            }
        }
        val fixture = File.createTempFile("orion_cortex_denied_", ".tmp", base.cacheDir)
        try {
            fixture.writeBytes(ByteArray(64))
            OrionCortexClient(context, AndroidOrionClock(base.contentResolver), OrionCortexAdmissionGuard { _, _ -> null }).use { client ->
                ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    assertNull(client.loadModel("fixture", descriptor, fixture.length()) { fail("No request was admitted.") })
                }
                assertNull(client.startInference("fixture", "synthetic", 8) { fail("No request was admitted.") })
                assertEquals(0, binds)
            }
        } finally { fixture.delete() }
    }
    @Test fun serviceIsPrivateDisposableAndNegotiatesWithoutLoadingAModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context.packageName, OrionCortexService::class.java.name)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, 0)
        assertFalse(info.exported); assertEquals(context.packageName + ":ai", info.processName)
        val connected = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        val bound = context.bindService(Intent(OrionCortexService.ACTION_BIND).setComponent(component), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val remote = OrionCortexRemote(checkNotNull(binder))
            assertEquals(1 to 0, remote.getProtocolInfo())
            assertEquals(OrionCortexHealth.UNLOADED, remote.getStatus())
            assertTrue(remote.healthCheck())
        } finally { context.unbindService(connection) }
    }
}
