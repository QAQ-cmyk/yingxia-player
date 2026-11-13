package cn.yingxia.player.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cn.yingxia.player.core.ThemeMode

/** Warm amber used on video, where the surface color scheme does not apply. */
val Accent = Color(0xFFE08A4F)

private val Light = lightColorScheme(
    primary = Color(0xFFC45C26), onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E4D4), onPrimaryContainer = Color(0xFF6A3414),
    secondary = Color(0xFF6E6258), secondaryContainer = Color(0xFFEFE8E1), onSecondaryContainer = Color(0xFF3E342C),
    background = Color(0xFFF7F4EF), surface = Color(0xFFF7F4EF),
    surfaceContainer = Color(0xFFEFEBE4), surfaceContainerLow = Color(0xFFFFFCF8),
    surfaceContainerHigh = Color(0xFFE7E1D8), surfaceVariant = Color(0xFFE8E2D9),
    onBackground = Color(0xFF1C1B19), onSurface = Color(0xFF1C1B19),
    onSurfaceVariant = Color(0xFF5E5952), outline = Color(0xFFD9D3C9), outlineVariant = Color(0xFFE4DED4))
private val Dark = darkColorScheme(
    primary = Color(0xFFF0A36A), onPrimary = Color(0xFF3D220C),
    primaryContainer = Color(0xFF5A3218), onPrimaryContainer = Color(0xFFFFE3CF),
    secondary = Color(0xFFD2C4B6), secondaryContainer = Color(0xFF3A332C), onSecondaryContainer = Color(0xFFF0E6DA),
    background = Color(0xFF12110F), surface = Color(0xFF12110F),
    surfaceContainer = Color(0xFF24221E), surfaceContainerLow = Color(0xFF1C1A17),
    surfaceContainerHigh = Color(0xFF2E2B26), surfaceVariant = Color(0xFF2A2723),
    onBackground = Color(0xFFF6F2EC), onSurface = Color(0xFFF6F2EC),
    onSurfaceVariant = Color(0xFFC9C2B8), outline = Color(0xFF4A453E), outlineVariant = Color(0xFF3A352F))

@Composable fun YingXiaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && isSystemInDarkTheme()
    val baseline = Typography()
    MaterialTheme(colorScheme = if (dark) Dark else Light,
        typography = baseline.copy(
            headlineSmall = baseline.headlineSmall.copy(fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
            titleLarge = baseline.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = baseline.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
            titleSmall = baseline.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = baseline.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = baseline.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = baseline.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = baseline.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
            labelMedium = baseline.labelMedium.copy(fontWeight = FontWeight.Medium),
            labelSmall = baseline.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp)), content = content)
}

fun <T> motionTween(durationMillis: Int = 220) = tween<T>(durationMillis, easing = FastOutSlowInEasing)

fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
fun fileSize(bytes: Long): String = if (bytes >= 1_073_741_824) "%.1f GB".format(bytes / 1_073_741_824.0) else "%.1f MB".format(bytes / 1_048_576.0)
fun speedText(speed: Float) = "${"%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')}×"
