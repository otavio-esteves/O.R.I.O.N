package network.orion.ai.service

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import network.orion.ai.api.*
import network.orion.ai.cortex.OrionCortexClient
import network.orion.core.time.AndroidOrionClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Binder/process lifecycle with an explicit non-native fixture engine; no LLM/hardware claim. */
@RunWith(AndroidJUnit4::class)
class OrionCortexLifecycleTest {
    @Test fun deathTerminatesOnceKeepsMainAliveAndRebindsOnlyForNewExplicitRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clock = AndroidOrionClock(context.contentResolver)
        val binds = AtomicInteger()
        val disconnected = CountDownLatch(1)
        val mainPid = Process.myPid()
        val guard = OrionCortexAdmissionGuard { model, operation ->
            if (operation == OrionCortexOperation.BIND) binds.incrementAndGet()
            OrionCortexApproval(model,512,1,8,clock.monotonicNow(),5_000,10_000)
        }
        val file = File.createTempFile("orion_cortex_lifecycle_", ".tmp", context.cacheDir)
        file.writeBytes(ByteArray(64))
        try {
            OrionCortexClient(context,clock,guard, { if (it == OrionCortexHealth.UNAVAILABLE) disconnected.countDown() }).use { client ->
                fun load() {
                    val terminal = CountDownLatch(1); val result = AtomicReference<OrionCortexRequestStatus>()
                    ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        assertNotNull(client.loadModel("fixture",fd,file.length(),10_000) { chunk ->
                            chunk.terminal?.let { result.set(it); terminal.countDown() }
                        })
                    }
                    assertTrue(terminal.await(15,TimeUnit.SECONDS)); assertEquals(OrionCortexRequestStatus.SUCCEEDED,result.get())
                }
                load()
                val started = CountDownLatch(1); val terminal = CountDownLatch(1)
                val terminalCount = AtomicInteger(); val result = AtomicReference<OrionCortexRequestStatus>()
                assertNotNull(client.startInference("fixture","WAIT_FOR_DEATH",8,timeoutMillis = 20_000) { chunk ->
                    if (chunk.textDelta != null) started.countDown()
                    chunk.terminal?.let { terminalCount.incrementAndGet(); result.set(it); terminal.countDown() }
                })
                assertTrue(started.await(10,TimeUnit.SECONDS))
                val manager = context.getSystemService(ActivityManager::class.java)
                val aiPid = checkNotNull(manager.runningAppProcesses.firstOrNull { it.processName == context.packageName + ":ai" }).pid
                assertNotEquals(mainPid,aiPid)
                Process.killProcess(aiPid)
                assertTrue(terminal.await(10,TimeUnit.SECONDS)); assertEquals(OrionCortexRequestStatus.PROCESS_DIED,result.get())
                assertTrue(disconnected.await(10,TimeUnit.SECONDS))
                assertEquals(1,terminalCount.get()); assertEquals(mainPid,Process.myPid()); assertEquals(1,binds.get())
                assertEquals(OrionCortexHealth.UNAVAILABLE,client.getStatus())
                load()
                assertEquals(2,binds.get()); assertEquals(1,terminalCount.get())
                val rebound = checkNotNull(manager.runningAppProcesses.firstOrNull { it.processName == context.packageName + ":ai" && it.pid != aiPid }).pid
                assertNotEquals(aiPid,rebound)
            }
        } finally { file.delete() }
    }
}
