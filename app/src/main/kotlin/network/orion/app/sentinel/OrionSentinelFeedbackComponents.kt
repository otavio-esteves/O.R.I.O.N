package network.orion.app.sentinel

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.core.sentinel.OrionProactivityFeedbackKind

private data class OrionSentinelFeedbackRoute(val id: UUID, val kind: OrionProactivityFeedbackKind)

private fun Intent.feedbackRoute(): OrionSentinelFeedbackRoute? {
    if (action != "network.orion.SENTINEL_FEEDBACK") return null
    val uri = data ?: return null
    if (uri.scheme != "orion" || uri.host != "sentinel" || uri.pathSegments.size != 2) return null
    val (rawId, rawKind) = uri.pathSegments
    val id = try { UUID.fromString(rawId) } catch (_: IllegalArgumentException) { return null }
    val kind = OrionSentinelRuntime.interactions.singleOrNull { it.name == rawKind } ?: return null
    if (dataString != "orion://sentinel/$id/${kind.name}") return null
    return OrionSentinelFeedbackRoute(id, kind)
}

class OrionSentinelFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val route = intent.feedbackRoute()?.takeUnless { it.kind == OrionProactivityFeedbackKind.OPENED } ?: return
        val runtime = (context.applicationContext as? OrionApplication)?.mainRuntime ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    runtime.receiveSentinelFeedback(route.id, route.kind)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Admission failure leaves any existing notification and eligibility state intact.
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Direct private Activity destination of the notification content PendingIntent. */
class OrionSentinelOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = intent.feedbackRoute()?.takeIf { it.kind == OrionProactivityFeedbackKind.OPENED }
        if (route != null) {
            (application as? OrionApplication)?.mainRuntime?.noteSentinelOpened(route.id)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(OPEN_TASKS, true))
        }
        finish()
    }

    companion object { internal const val OPEN_TASKS = "network.orion.OPEN_TASKS" }
}
