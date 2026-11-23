package network.orion.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun HomeScreen(shortcuts: List<String>, onOpen: (String) -> Unit) {
    var optionsVisible by rememberSaveable { mutableStateOf(false) }
    val pose = remember { OrionGlobePose() }
    var draggingGlobe by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    BackHandler(enabled = optionsVisible) { optionsVisible = false }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("orion_home")
            .pointerInput(pose) {
                val threshold = 36.dp.toPx()
                val radiansPerPixel = 1f / 110.dp.toPx()
                var travel = Offset.Zero
                detectDragGestures(
                    onDragStart = { touch ->
                        travel = Offset.Zero
                        draggingGlobe = !optionsVisible
                        if (draggingGlobe) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val radius = minOf(size.width * 0.73f, size.height * 0.73f, 320.dp.toPx()) * 0.49f
                            pose.touch(touch - center, radius)
                        }
                    },
                    onDragCancel = { draggingGlobe = false },
                    onDragEnd = {
                        draggingGlobe = false
                        if (abs(travel.y) >= threshold && abs(travel.y) > abs(travel.x)) {
                            optionsVisible = !optionsVisible && travel.y < 0f
                        }
                    },
                ) { change, delta ->
                    change.consume()
                    travel += delta
                    if (draggingGlobe) {
                        val seconds = (change.uptimeMillis - change.previousUptimeMillis).coerceIn(8L, 100L) / 1_000f
                        pose.drag(delta * radiansPerPixel, seconds)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val diameter = minOf(maxWidth * 0.73f, maxHeight * 0.73f, 320.dp)
        val optionsHeight = maxHeight * 0.86f
        AnimatedVisibility(
            visible = !optionsVisible,
            enter = fadeIn(tween(145)),
            exit = fadeOut(tween(110)),
        ) {
            OrionGlobe(
                animate = windowFocused && !optionsVisible && !draggingGlobe,
                pose = pose,
                modifier = Modifier
                    .size(diameter)
                    .testTag("orion_globe")
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Abrir opções",
                        onClick = { optionsVisible = true },
                    )
                    .semantics { contentDescription = "Orion" },
            )
        }

        AnimatedVisibility(
            visible = optionsVisible,
            modifier = Modifier.align(Alignment.Center).widthIn(max = 426.dp),
            enter = fadeIn(tween(145, delayMillis = 35)) +
                slideInVertically(tween(185, easing = FastOutSlowInEasing)) { it / 16 },
            exit = fadeOut(tween(105)) + slideOutVertically(tween(130)) { it / 20 },
        ) {
            OrionOptions(
                shortcuts = shortcuts,
                onDismiss = { optionsVisible = false },
                onOpen = { label ->
                    optionsVisible = false
                    onOpen(label)
                },
                modifier = Modifier.heightIn(max = optionsHeight),
            )
        }
    }
}

@Composable
private fun OrionOptions(
    shortcuts: List<String>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    val featured = shortcuts.filter { it == "Chat" || it == "Voz" }
    val other = shortcuts - featured.toSet()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scroll, enabled = scroll.maxValue > 0)
            .padding(horizontal = 24.dp)
            .testTag("orion_options")
            .semantics { paneTitle = "Opções" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("O.R.I.O.N.", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        if (featured.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (label in featured) {
                    OrionShortcut(label, onOpen, Modifier.weight(1f), featured = true)
                }
            }
        }
        if (featured.isNotEmpty() && other.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(2.dp))
        }
        for (row in other.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.size == 1) Spacer(Modifier.weight(0.5f))
                for (label in row) {
                    OrionShortcut(label, onOpen, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(0.5f))
            }
        }
        TextButton(onClick = onDismiss) {
            Text(
                "Voltar",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun OrionShortcut(label: String, onOpen: (String) -> Unit, modifier: Modifier, featured: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { onOpen(label) },
        modifier = modifier.heightIn(min = if (featured) 76.dp else 48.dp),
        shape = MaterialTheme.shapes.small,
        color = if (featured) colors.surfaceContainerLow else colors.surface,
        border = BorderStroke(1.dp, if (featured) colors.outline else colors.outlineVariant),
    ) {
        if (featured) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OrionShortcutIcon(microphone = label == "Voz", color = colors.tertiary)
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun OrionShortcutIcon(microphone: Boolean, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.35.dp.toPx()
        if (microphone) {
            val bars = floatArrayOf(0.22f, 0.56f, 0.87f, 0.56f, 0.22f)
            bars.forEachIndexed { index, bar ->
                val x = size.width * (0.10f + index * 0.20f)
                val half = size.height * bar / 2f
                drawLine(color, Offset(x, center.y - half), Offset(x, center.y + half), stroke, StrokeCap.Round)
            }
        } else {
            val unit = size.width / 20f
            val bubble = androidx.compose.ui.graphics.Path().apply {
                moveTo(3f * unit, 3f * unit)
                lineTo(17f * unit, 3f * unit)
                quadraticTo(18f * unit, 3f * unit, 18f * unit, 4f * unit)
                lineTo(18f * unit, 13f * unit)
                quadraticTo(18f * unit, 14f * unit, 17f * unit, 14f * unit)
                lineTo(8f * unit, 14f * unit)
                lineTo(3f * unit, 18f * unit)
                close()
            }
            drawPath(bubble, color, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}
