package network.orion.voice.service

import android.Manifest
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Handles an explicit tap inside the Android assistant window in :voice_session. */
internal class OrionVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val app = context.applicationContext
    private val roleManager = app.getSystemService(RoleManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val assistant = ComponentName(app, OrionVoiceInteractionService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var shown = false
    @Volatile private var windowFocused = false
    private var roleWatch: Job? = null
    private var capture: OrionVoiceCapture? = null
    private var panel: Panel? = null
    private var state: OrionVoiceSessionState? = null
    private var reply: String? = null
    private var notice: Int? = null

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(assistantAvailable())
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        // Neither the invocation extras nor the supplied Assist context authorize capture.
        clearCapture()
        roleWatch?.cancel()
        shown = assistantAvailable()
        if (!shown) {
            hide()
            return
        }
        render()
        roleWatch = scope.launch {
            while (shown) {
                delay(500)
                if (!assistantAvailable()) {
                    conceal()
                    hide()
                } else if (capture != null && !hasRecordingPermission()) {
                    clearCapture()
                    render()
                }
            }
        }
    }

    override fun onCreateContentView(): View {
        window.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        return ScrollView(context).apply {
            addView(Panel(context).also { panel = it })
        }
    }

    override fun onHandleAssist(state: AssistState) = Unit

    override fun onHandleScreenshot(screenshot: Bitmap?) = Unit

    override fun onLockscreenShown() {
        conceal()
        super.onLockscreenShown()
    }

    override fun onHide() {
        conceal()
        super.onHide()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    fun close() {
        conceal()
        scope.cancel()
        panel = null
    }

    private fun conceal() {
        shown = false
        clearCapture()
        roleWatch?.cancel()
        roleWatch = null
        render()
    }

    private fun assistantAvailable(): Boolean = try {
        keyguard?.isKeyguardLocked == false &&
            roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true &&
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) &&
            VoiceInteractionService.isActiveService(app, assistant)
    } catch (_: SecurityException) {
        false
    }

    private fun hasMicrophone() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    private fun hasRecordingPermission() =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun mayCapture() = shown && windowFocused && assistantAvailable() && hasRecordingPermission()

    private fun startCapture() {
        if (capture != null || !mayCapture() || !hasMicrophone() || !OrionVoiceModel.present(app)) {
            render()
            return
        }
        notice = null
        reply = null
        state = OrionVoiceSessionState.CONNECTING
        val id = UUID.randomUUID()
        lateinit var owner: OrionVoiceCapture
        owner = OrionVoiceCapture(app, scope, id, ::mayCapture) { next, text, _ ->
            if (shown && windowFocused && capture === owner) {
                state = next
                if (text != null) reply = text
                render()
            }
        }
        capture = owner
        render()
        owner.start {
            if (capture === owner) {
                capture = null
                render()
            }
        }
    }

    private fun clearCapture() {
        val previous = capture
        capture = null
        previous?.cancel()
        state = null
        reply = null
        notice = null
    }

    private fun openApp() {
        if (!shown || !windowFocused || !assistantAvailable()) return
        clearCapture()
        val launcher = app.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launcher?.component?.packageName == app.packageName) {
            try {
                startAssistantActivity(launcher)
                hide()
                return
            } catch (_: RuntimeException) {
                // Android can dismiss the session before executing the navigation.
            }
        }
        notice = R.string.orion_assistant_open_failed
        render()
    }

    private fun render() {
        val ui = panel ?: return
        val active = shown && windowFocused
        val busy = capture != null && state?.terminal != true
        val modelReady = OrionVoiceModel.present(app)
        val microphone = hasMicrophone()
        val permission = hasRecordingPermission()
        ui.talk.isEnabled = active && capture == null && modelReady && microphone && permission
        ui.finish.visibility = if (busy && state == OrionVoiceSessionState.LISTENING)
            View.VISIBLE else View.GONE
        ui.cancel.visibility = if (busy) View.VISIBLE else View.GONE
        ui.open.isEnabled = active
        val statusId = notice ?: when (state) {
            null -> when {
                !microphone -> R.string.orion_assistant_microphone_unavailable
                !modelReady -> R.string.orion_assistant_no_model
                !permission -> R.string.orion_assistant_permission
                else -> R.string.orion_assistant_ready
            }
            OrionVoiceSessionState.CONNECTING -> R.string.orion_assistant_connecting
            OrionVoiceSessionState.LISTENING -> R.string.orion_assistant_listening
            OrionVoiceSessionState.TRANSCRIBING -> R.string.orion_assistant_transcribing
            OrionVoiceSessionState.WAITING_FOR_CORE -> R.string.orion_assistant_waiting
            OrionVoiceSessionState.CANCELLED -> R.string.orion_assistant_cancelled
            OrionVoiceSessionState.NO_SPEECH -> R.string.orion_assistant_no_speech
            OrionVoiceSessionState.MODEL_UNAVAILABLE -> R.string.orion_assistant_no_model
            OrionVoiceSessionState.MICROPHONE_UNAVAILABLE -> R.string.orion_assistant_microphone_unavailable
            OrionVoiceSessionState.CORE_UNAVAILABLE -> R.string.orion_assistant_core_unavailable
            OrionVoiceSessionState.FAILED -> R.string.orion_assistant_failed
            OrionVoiceSessionState.RESOURCE_BUSY -> R.string.orion_assistant_resource_busy
            OrionVoiceSessionState.SPEAKING, OrionVoiceSessionState.SUCCEEDED -> null
        }
        ui.status.text = statusId?.let { context.getString(it) }
        ui.status.visibility = if (statusId != null) View.VISIBLE else View.GONE
        ui.response.text = reply
        ui.response.visibility = if (reply != null) View.VISIBLE else View.GONE
    }

    private inner class Panel(context: Context) : LinearLayout(context) {
        val status = TextView(context)
        val response = TextView(context)
        val talk = button(R.string.orion_assistant_talk, ::startCapture)
        val finish = button(R.string.orion_assistant_finish_capture) { capture?.finishCapture() }
        val cancel = button(R.string.orion_assistant_cancel) {
            clearCapture()
            state = OrionVoiceSessionState.CANCELLED
            render()
        }
        val open = button(R.string.orion_assistant_open_app, ::openApp)

        init {
            orientation = VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                setText(R.string.orion_assistant_title)
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large)
                isAccessibilityHeading = true
            })
            addView(status)
            addView(response)
            addView(talk)
            addView(finish)
            addView(cancel)
            addView(open)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            windowFocused = hasWindowFocus
            if (!hasWindowFocus) clearCapture()
            render()
        }

        private fun button(label: Int, action: () -> Unit) = Button(context).apply {
            isAllCaps = false
            filterTouchesWhenObscured = true
            setText(label)
            setOnClickListener { action() }
        }
    }
}
