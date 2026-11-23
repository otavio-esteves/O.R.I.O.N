package network.orion.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(shortcuts: List<String>, onOpen: (String) -> Unit) {
    var optionsVisible by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("orion_home")
            .pointerInput(Unit) {
                val threshold = 36.dp.toPx()
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = { if (travel <= -threshold) optionsVisible = true },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        travel += dragAmount
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        OrionGlobe(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(0.73f)
                .aspectRatio(1f)
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

    if (optionsVisible) {
        ModalBottomSheet(
            onDismissRequest = { optionsVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.Black,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("orion_options")
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                shortcuts.chunked(2).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { label ->
                            TextButton(
                                onClick = {
                                    optionsVisible = false
                                    onOpen(label)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(label, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrionGlobe(modifier: Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension * 0.49f
        for (point in globePoints) {
            drawCircle(
                color = point.color,
                radius = radius * point.radius,
                center = center + point.offset * radius,
            )
        }
    }
}

private data class GlobePoint(val offset: Offset, val radius: Float, val color: Color)

private val globePoints = List(1_680) { index ->
    val latitude = 1f - (index + 0.5f) / 840f
    val ring = sqrt(1f - latitude * latitude)
    val angle = index * PI * (3.0 - sqrt(5.0))
    val x = ring * cos(angle).toFloat()
    val depth = ring * sin(angle).toFloat()
    val perspective = 1f / (1.03f - 0.16f * depth)
    val light = (-0.26f * x + 0.44f * latitude + 0.86f * depth).coerceIn(0f, 1f)
    val intensity = if (depth < 0f) 0.10f + 0.08f * (1f + depth) else 0.30f + 0.65f * light
    GlobePoint(
        offset = Offset(x * perspective, -latitude * perspective),
        radius = (if (depth < 0f) 0.0045f else 0.007f + 0.006f * light) * perspective,
        color = Color(intensity, intensity, intensity),
    ) to depth
}.sortedBy { it.second }.map { it.first }
