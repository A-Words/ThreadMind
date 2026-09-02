package app.threadmind.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ThreadMindPurple = Color(0xFF6F4FB3)
private val ThreadMindPurpleDark = Color(0xFF5A3C9E)
private val ThreadMindPurpleLight = Color(0xFFD6BCFF)

private val LightColors = lightColorScheme(
    primary = ThreadMindPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    onPrimaryContainer = Color(0xFF25005A),
    secondary = Color(0xFF655A70),
    background = Color(0xFFFFF9FF),
    surface = Color(0xFFFFF9FF),
    surfaceVariant = Color(0xFFE9E0EB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F2FA),
    surfaceContainer = Color(0xFFF2ECF4),
    surfaceContainerHigh = Color(0xFFECE6EE),
    surfaceContainerHighest = Color(0xFFE6E0E8),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = ThreadMindPurpleLight,
    onPrimary = Color(0xFF3D177D),
    primaryContainer = ThreadMindPurpleDark,
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFCFC1D8),
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
    surfaceVariant = Color(0xFF49454E),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1C1921),
    surfaceContainer = Color(0xFF211E26),
    surfaceContainerHigh = Color(0xFF2B2830),
    surfaceContainerHighest = Color(0xFF36333B),
    error = Color(0xFFFFB4AB),
)

private val ThreadMindShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val ThreadMindTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
)

object ThreadMindSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val xLarge = 32.dp
}

@Composable
fun ThreadMindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = ThreadMindShapes,
        typography = ThreadMindTypography,
        content = content,
    )
}
