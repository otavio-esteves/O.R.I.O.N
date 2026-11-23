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
private const val SIGNAL_SHADE_COUNT = 4
private const val SIGNAL_CYCLE_SECONDS = 6.4f
private const val SIGNAL_STEP_SECONDS = 0.11f
private const val SIGNAL_LIFETIME_SECONDS = 0.48f

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
                pose.advance(elapsedSeconds / durationScale)
                previousFrame = frame
            }
        }
    }

    Canvas(modifier) {
        val radius = size.minDimension * 0.49f
        drawIntoCanvas { canvas ->
            renderer.draw(canvas.nativeCanvas, center, radius, pose.yaw, pose.pitch, pose.signalTime)
        }
    }
}

internal class OrionGlobePose {
    var yaw by mutableFloatStateOf(0f)
        private set
    var pitch by mutableFloatStateOf(0f)
        private set
    var signalTime by mutableFloatStateOf(0f)
        private set

    fun advance(seconds: Float) {
        yaw = (yaw + seconds * FULL_TURN / TURN_SECONDS) % FULL_TURN
        signalTime = (signalTime + seconds) % SIGNAL_CYCLE_SECONDS
    }

    fun drag(radians: Offset) {
        yaw = (yaw + radians.x) % FULL_TURN
        pitch = (pitch + radians.y).coerceIn(-1.2f, 1.2f)
    }
}

private class OrionGlobeRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val coordinates = Array(SHADE_COUNT + SIGNAL_SHADE_COUNT) { FloatArray(DOT_COUNT * 2) }
    private val usedFloats = IntArray(coordinates.size)
    private val colors = IntArray(coordinates.size) { shade ->
        if (shade < SHADE_COUNT) {
            val gray = 21 + 221 * shade / (SHADE_COUNT - 1)
            AndroidColor.rgb(gray, gray, gray)
        } else {
            val signal = shade - SHADE_COUNT
            AndroidColor.rgb(139 + 27 * signal, 169 + 23 * signal, 210 + 14 * signal)
        }
    }
    private val signal = FloatArray(DOT_COUNT)
    private val litFront = BooleanArray(DOT_COUNT)
    private val projected = FloatArray(DOT_COUNT * 2)
    private val links = FloatArray(signalPaths.sumOf { (it.points.size - 1) * 4 })

    fun draw(canvas: AndroidCanvas, center: Offset, radius: Float, yaw: Float, pitch: Float, time: Float) {
        val yawCos = cos(yaw)
        val yawSin = sin(yaw)
        val pitchCos = cos(pitch)
        val pitchSin = sin(pitch)
        updateSignal(time)
        usedFloats.fill(0)
        litFront.fill(false)

        // Group the projected dots by brightness so Canvas draws each shade in one batch.
        for (index in sphere.indices) {
            val point = sphere[index]
            val x = point.x * yawCos + point.z * yawSin
            val horizontalDepth = point.z * yawCos - point.x * yawSin
            val y = point.y * pitchCos - horizontalDepth * pitchSin
            val depth = horizontalDepth * pitchCos + point.y * pitchSin
            val light = (-0.26f * x + 0.44f * y + 0.86f * depth).coerceIn(0f, 1f)
            val ambient = 0.09f + 0.08f * (1f + depth)
            val facing = ((depth + 0.04f) / 0.25f).coerceIn(0f, 1f)
            val brightness = ambient + facing * (0.30f + 0.64f * light - ambient)
            val scale = radius / (1.03f - 0.16f * depth)
            val screenX = center.x + x * scale
            val screenY = center.y - y * scale
            val energy = signal[index] * facing
            val shade = if (depth > 0.07f && energy >= 0.20f) {
                litFront[index] = true
                projected[index * 2] = screenX
                projected[index * 2 + 1] = screenY
                SHADE_COUNT + (energy * SIGNAL_SHADE_COUNT).toInt().coerceAtMost(SIGNAL_SHADE_COUNT - 1)
            } else {
                ((brightness + 0.25f * energy - 0.08f) * (SHADE_COUNT - 1) / 0.87f)
                    .roundToInt().coerceIn(0, SHADE_COUNT - 1)
            }

            val offset = usedFloats[shade]
            coordinates[shade][offset] = screenX
            coordinates[shade][offset + 1] = screenY
            usedFloats[shade] = offset + 2
        }

        drawLinks(canvas, radius)
        for (shade in colors.indices) {
            val count = usedFloats[shade]
            if (count == 0) continue
            paint.color = colors[shade]
            paint.strokeWidth = radius * if (shade < SHADE_COUNT) {
                0.009f + 0.017f * shade / (SHADE_COUNT - 1)
            } else {
                0.021f + 0.004f * (shade - SHADE_COUNT)
            }
            canvas.drawPoints(coordinates[shade], 0, count, paint)
        }
    }

    private fun updateSignal(time: Float) {
        signal.fill(0f)
        for (path in signalPaths) {
            var age = (time + path.phase) % SIGNAL_CYCLE_SECONDS
            for (point in path.points) {
                if (age < 0f) break
                if (age < SIGNAL_LIFETIME_SECONDS) {
                    val rise = (age / 0.08f).coerceAtMost(1f)
                    val fall = 1f - age / SIGNAL_LIFETIME_SECONDS
                    val strength = (1.4f * rise * fall * fall).coerceAtMost(1f)
                    signal[point] = maxOf(signal[point], strength)
                }
                age -= SIGNAL_STEP_SECONDS
            }
        }
    }

    private fun drawLinks(canvas: AndroidCanvas, radius: Float) {
        var count = 0
        for (path in signalPaths) {
            for (step in 1 until path.points.size) {
                val from = path.points[step - 1]
                val to = path.points[step]
                if (!litFront[from] || !litFront[to]) continue
                links[count++] = projected[from * 2]
                links[count++] = projected[from * 2 + 1]
                links[count++] = projected[to * 2]
                links[count++] = projected[to * 2 + 1]
            }
        }
        if (count == 0) return
        paint.color = 0x626991C5
        paint.strokeWidth = (radius * 0.004f).coerceAtLeast(1f)
        canvas.drawLines(links, 0, count, paint)
    }
}

private data class SpherePoint(val x: Float, val y: Float, val z: Float)

private val sphere = List(DOT_COUNT) { index ->
    val y = 1f - 2f * (index + 0.5f) / DOT_COUNT
    val ring = sqrt(1f - y * y)
    val azimuth = index * PI * (3.0 - sqrt(5.0))
    SpherePoint(ring * cos(azimuth).toFloat(), y, ring * sin(azimuth).toFloat())
}

private data class SignalPath(val points: IntArray, val phase: Float)

// Fibonacci index offsets are candidate neighbors. Choose the closest forward point once.
private val neighborOffsets = intArrayOf(8, 13, 21, 34, 55, 89)
private val signalSeeds = intArrayOf(153, 254, 371, 497, 627, 755, 882, 996, 1116, 1243, 1376, 1472)
private val signalPaths = signalSeeds.mapIndexed { index, seed ->
    val direction = if (index % 2 == 0) 1 else -1
    val route = mutableListOf(seed)
    while (route.size < 14) {
        val current = route.last()
        val origin = sphere[current]
        var nearest = -1
        var greatestDot = 0.98f
        for (offset in neighborOffsets) {
            val candidate = current + direction * offset
            if (candidate !in sphere.indices) continue
            val target = sphere[candidate]
            val dot = origin.x * target.x + origin.y * target.y + origin.z * target.z
            if (dot > greatestDot) {
                nearest = candidate
                greatestDot = dot
            }
        }
        if (nearest < 0) break
        route += nearest
    }
    SignalPath(route.toIntArray(), index * SIGNAL_CYCLE_SECONDS / signalSeeds.size)
}
