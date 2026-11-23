package network.orion.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(shortcuts: List<String>, onOpen: (String) -> Unit) {
    var optionsVisible by rememberSaveable { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    BackHandler(enabled = optionsVisible) { optionsVisible = false }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("orion_home")
            .pointerInput(Unit) {
                val threshold = 36.dp.toPx()
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = {
                        if (travel <= -threshold) optionsVisible = true
                        if (travel >= threshold) optionsVisible = false
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        travel += dragAmount
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val diameter = minOf(maxWidth * 0.73f, maxHeight * 0.73f, 320.dp)
        val optionsHeight = maxHeight * 0.9f
        AnimatedVisibility(
            visible = !optionsVisible,
            enter = fadeIn(tween(145)),
            exit = fadeOut(tween(110)),
        ) {
            OrionGlobe(
                animate = windowFocused && !optionsVisible,
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
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp),
            enter = slideInVertically(tween(195, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(145)),
            exit = slideOutVertically(tween(140)) { it / 8 } + fadeOut(tween(110)),
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
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("orion_options")
            .semantics { paneTitle = "Opções" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = "Fechar opções" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            shortcuts.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { label ->
                        TextButton(onClick = { onOpen(label) }, modifier = Modifier.weight(1f)) {
                            Text(label, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}
