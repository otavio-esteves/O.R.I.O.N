package network.orion.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val orionColors = darkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF09090B),
    primaryContainer = Color(0xFF182234),
    onPrimaryContainer = Color(0xFFC8DCFF),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF09090B),
    secondaryContainer = Color(0xFF242428),
    onSecondaryContainer = Color(0xFFF4F4F5),
    tertiary = Color(0xFFC8DCFF),
    onTertiary = Color(0xFF111C2A),
    background = Color.Black,
    onBackground = Color(0xFFF4F4F5),
    surface = Color.Black,
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1A1A1D),
    onSurfaceVariant = Color(0xFFA9A9B1),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C0C0E),
    surfaceContainer = Color(0xFF121215),
    surfaceContainerHigh = Color(0xFF19191D),
    surfaceContainerHighest = Color(0xFF242428),
    surfaceBright = Color(0xFF29292E),
    outline = Color(0xFF6B6B75),
    outlineVariant = Color(0xFF29292F),
    error = Color(0xFFFFB4AE),
    errorContainer = Color(0xFF451A1A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val orionShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

private val defaults = Typography()
private val orionTypography = Typography(
    headlineSmall = defaults.headlineSmall.copy(fontSize = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp),
    titleLarge = defaults.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleMedium = defaults.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyMedium = defaults.bodyMedium.copy(lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelLarge = defaults.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

@Composable
internal fun OrionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = orionColors, shapes = orionShapes, typography = orionTypography, content = content)
}
