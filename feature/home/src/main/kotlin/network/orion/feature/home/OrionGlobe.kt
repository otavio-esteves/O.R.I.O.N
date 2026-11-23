package network.orion.feature.home

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val FULL_TURN = 6.2831855f
private const val TURN_SECONDS = 32f
private const val FRAME_INTERVAL_MS = 34L
private const val DOT_COUNT = 1_680
private const val SHADE_COUNT = 16

@Composable
internal fun OrionGlobe(animate: Boolean, pose: OrionGlobePose, modifier: Modifier) {
    val renderer = remember { OrionGlobeRenderer() }

    LaunchedEffect(animate, pose) {
        if (!animate) return@LaunchedEffect
        val motion = coroutineContext[MotionDurationScale]
        // Compose initializes the platform observer on first read, which can write snapshot state.
        val initialScale = motion?.scaleFactor ?: 1f
        snapshotFlow { motion?.scaleFactor ?: initialScale }.collectLatest { durationScale ->
            if (durationScale <= 0f) return@collectLatest
            var previousFrame = withInfiniteAnimationFrameNanos { it }
            while (true) {
                delay(FRAME_INTERVAL_MS)
                val frame = withInfiniteAnimationFrameNanos { it }
                val elapsedSeconds = (frame - previousFrame).coerceAtMost(100_000_000L) / 1_000_000_000f
                val step = elapsedSeconds * FULL_TURN / TURN_SECONDS / durationScale
                pose.rotate(step)
                previousFrame = frame
            }
        }
    }

    Canvas(modifier) {
        val radius = size.minDimension * 0.49f
        drawIntoCanvas { canvas -> renderer.draw(canvas.nativeCanvas, center, radius, pose.yaw, pose.pitch) }
    }
}

internal class OrionGlobePose {
    var yaw by mutableFloatStateOf(0f)
        private set
    var pitch by mutableFloatStateOf(0f)
        private set

    fun rotate(radians: Float) {
        yaw = (yaw + radians) % FULL_TURN
    }

    fun drag(radians: Offset) {
        rotate(radians.x)
        pitch = (pitch + radians.y).coerceIn(-1.2f, 1.2f)
    }
}

private class OrionGlobeRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val coordinates = Array(SHADE_COUNT) { FloatArray(DOT_COUNT * 2) }
    private val usedFloats = IntArray(SHADE_COUNT)
    private val colors = IntArray(SHADE_COUNT) { shade ->
        val gray = 21 + 221 * shade / (SHADE_COUNT - 1)
        AndroidColor.rgb(gray, gray, gray)
    }

    fun draw(canvas: AndroidCanvas, center: Offset, radius: Float, yaw: Float, pitch: Float) {
        val yawCos = cos(yaw)
        val yawSin = sin(yaw)
        val pitchCos = cos(pitch)
        val pitchSin = sin(pitch)
        usedFloats.fill(0)

        // Group the projected dots by brightness so Canvas draws each shade in one batch.
        for (point in sphere) {
            val x = point.x * yawCos + point.z * yawSin
            val horizontalDepth = point.z * yawCos - point.x * yawSin
            val y = point.y * pitchCos - horizontalDepth * pitchSin
            val depth = horizontalDepth * pitchCos + point.y * pitchSin
            val light = (-0.26f * x + 0.44f * y + 0.86f * depth).coerceIn(0f, 1f)
            val ambient = 0.09f + 0.08f * (1f + depth)
            val facing = ((depth + 0.04f) / 0.25f).coerceIn(0f, 1f)
            val brightness = ambient + facing * (0.30f + 0.64f * light - ambient)
            val shade = ((brightness - 0.08f) * (SHADE_COUNT - 1) / 0.87f)
                .roundToInt().coerceIn(colors.indices)
            val scale = radius / (1.03f - 0.16f * depth)

            val offset = usedFloats[shade]
            coordinates[shade][offset] = center.x + x * scale
            coordinates[shade][offset + 1] = center.y - y * scale
            usedFloats[shade] = offset + 2
        }

        for (shade in colors.indices) {
            val count = usedFloats[shade]
            if (count == 0) continue
            paint.color = colors[shade]
            paint.strokeWidth = radius * (0.009f + 0.017f * shade / (SHADE_COUNT - 1))
            canvas.drawPoints(coordinates[shade], 0, count, paint)
        }
    }
}

private data class SpherePoint(val x: Float, val y: Float, val z: Float)

private val sphere = List(DOT_COUNT) { index ->
    val y = 1f - 2f * (index + 0.5f) / DOT_COUNT
    val ring = sqrt(1f - y * y)
    val azimuth = index * PI * (3.0 - sqrt(5.0))
    SpherePoint(ring * cos(azimuth).toFloat(), y, ring * sin(azimuth).toFloat())
}
