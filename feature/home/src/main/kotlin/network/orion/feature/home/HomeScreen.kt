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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
                    onDragStart = {
                        travel = Offset.Zero
                        draggingGlobe = !optionsVisible
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
                    if (draggingGlobe) pose.drag(delta * radiansPerPixel)
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
            modifier = Modifier.align(Alignment.Center).widthIn(max = 430.dp),
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scroll, enabled = scroll.maxValue > 0)
            .padding(horizontal = 28.dp)
            .testTag("orion_options")
            .semantics { paneTitle = "Opções" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        for (row in shortcuts.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (row.size == 1) Spacer(Modifier.weight(0.5f))
                for (label in row) {
                    OutlinedButton(
                        onClick = { onOpen(label) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFF303030)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(label, color = MaterialTheme.colorScheme.onSurface)
                    }
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
