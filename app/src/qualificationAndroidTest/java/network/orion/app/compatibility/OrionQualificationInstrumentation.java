package network.orion.app.compatibility;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Platform-only black-box instrumentation: no dependency on the target's shrunk libraries. */
public final class OrionQualificationInstrumentation extends Instrumentation {
    private static final String TARGET = "network.orion.qualification";
    private boolean nativeOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        nativeOnly = arguments != null && "true".equals(arguments.getString("orionNativeOnly"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            check(TARGET.equals(context.getPackageName()), "Wrong qualification identity");
            check((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0,
                    "Qualification target is debuggable");
            probeNative(context);
            if (!nativeOnly) probeStartup(context);
            result.putString("stream", "\nOK (" + (nativeOnly ? 1 : 2) + " tests)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void probeNative(Context context) throws Exception {
        check(Process.is64Bit(), "Expected a 64-bit process");
        String arch = System.getProperty("os.arch");
        String abi;
        if ("aarch64".equals(arch)) abi = "arm64-v8a";
        else if ("x86_64".equals(arch)) abi = "x86_64";
        else throw new AssertionError("Unsupported process ABI");
        boolean supported = false;
        for (String candidate : Build.SUPPORTED_64_BIT_ABIS) if (abi.equals(candidate)) supported = true;
        check(supported, "Process ABI absent from device capabilities");
        String source = context.getApplicationInfo().sourceDir;
        String prefix = "lib/" + abi + "/";
        ArrayList<String> libraries = new ArrayList<>();
        try (ZipFile apk = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (entry.startsWith(prefix) && entry.endsWith(".so")) {
                    String name = entry.substring(prefix.length());
                    check(name.matches("lib[A-Za-z0-9_.-]+\\.so"), "Unsupported native entry");
                    libraries.add(name);
                }
            }
        }
        check(!libraries.isEmpty(), "No packaged libraries for the process ABI");
        Collections.sort(libraries);
        for (String library : libraries) System.load(source + "!/" + prefix + library);
        long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        check(pageSize == 4096 || pageSize == 16384, "Unsupported runtime page size");
        Bundle status = new Bundle();
        status.putString("orion_native_load", "LOADED_ALL_PACKAGED_LIBRARIES");
        status.putString("orion_native_load_source", "EXACT_TARGET_APK_ENTRY");
        status.putString("orion_native_abi", abi);
        status.putString("orion_native_page_size", Long.toString(pageSize));
        status.putString("orion_native_libraries", String.join(",", libraries));
        sendStatus(0, status);
    }

    private void probeStartup(Context context) throws Exception {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(TARGET);
        check(intent != null, "No launch intent");
        Activity activity = startActivitySync(intent);
        try {
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            AccessibilityNodeInfo tab = awaitText("Diagnóstico", deadline);
            boolean clicked = false;
            for (int parents = 0; tab != null && parents < 8; parents++, tab = tab.getParent()) {
                if (tab.isClickable()) { clicked = tab.performAction(AccessibilityNodeInfo.ACTION_CLICK); break; }
            }
            check(clicked, "Diagnostics tab did not accept the click");
            boolean recovered = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (findText("Core: DEGRADED_SAFE") != null || findText("Core: READY") != null) {
                    recovered = true; break;
                }
                Thread.sleep(50);
            }
            check(recovered, "Core did not recover within the bounded interval");
            boolean healthy = findText("DATABASE: HEALTHY") != null;
            for (int scrolls = 0; !healthy && scrolls < 12 && SystemClock.elapsedRealtime() < deadline; scrolls++) {
                AccessibilityNodeInfo scroll = findNode(null, true);
                if (scroll == null) break;
                // Compose accessibility scroll actions are asynchronous. A false
                // response while an earlier scroll settles is not proof of the
                // end of the list. Wait for the exact assertion, then retry only
                // this bounded read/navigation step; never relaunch the app.
                scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                long settleDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 2_000);
                do {
                    healthy = findText("DATABASE: HEALTHY") != null;
                    if (!healthy) Thread.sleep(50);
                } while (!healthy && SystemClock.elapsedRealtime() < settleDeadline);
            }
            check(healthy, "Healthy database diagnostics were not observed");
            Bundle status = new Bundle();
            status.putString("orion_release_startup", "NON_DEBUGGABLE_STORAGE_HEALTHY");
            sendStatus(0, status);
        } finally { runOnMainSync(activity::finish); }
    }

    private AccessibilityNodeInfo awaitText(String text, long deadline) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo found = findText(text);
            if (found != null) return found;
            Thread.sleep(50);
        }
        throw new AssertionError("Expected qualification UI was not observed");
    }

    private AccessibilityNodeInfo findText(String text) { return findNode(text, false); }

    private AccessibilityNodeInfo findNode(String text, boolean scrollable) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null || !TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        for (int visited = 0; !queue.isEmpty() && visited < 2048; visited++) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (scrollable ? node.isScrollable() : text.contentEquals(node.getText() == null ? "" : node.getText())) return node;
            for (int child = 0; child < node.getChildCount(); child++) {
                AccessibilityNodeInfo value = node.getChild(child);
                if (value != null) queue.addLast(value);
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
