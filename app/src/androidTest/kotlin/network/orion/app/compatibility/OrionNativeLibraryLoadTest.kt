package network.orion.app.compatibility

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual linker/JNI load; static alignment and emulator execution never imply physical 16 KiB approval. */
@RunWith(AndroidJUnit4::class)
class OrionNativeLibraryLoadTest {
    @Test fun everyPackagedLibraryLoadsInTheActualProcessAbi() {
        assertTrue(Process.is64Bit())
        val abi = when (System.getProperty("os.arch")) {
            "aarch64" -> "arm64-v8a"
            "x86_64" -> "x86_64"
            else -> error("The qualification process ABI is unsupported")
        }
        assertTrue(abi in Build.SUPPORTED_64_BIT_ABIS)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val libraries = ZipFile(context.applicationInfo.sourceDir).use { apk ->
            apk.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/$abi/") && it.endsWith(".so") }
                .map { it.removePrefix("lib/$abi/") }.sorted().toList()
        }
        assertTrue("The active native gate must exercise packaged libraries", libraries.isNotEmpty())
        libraries.forEach { name ->
            require(Regex("lib[A-Za-z0-9_.-]+\\.so").matches(name))
            System.loadLibrary(name.removePrefix("lib").removeSuffix(".so"))
        }
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assertTrue(pageSize == 4096L || pageSize == 16384L)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("orion_native_load", "LOADED_ALL_PACKAGED_LIBRARIES")
            putString("orion_native_abi", abi)
            putString("orion_native_page_size", pageSize.toString())
            putString("orion_native_libraries", libraries.joinToString(","))
        })
    }
}
