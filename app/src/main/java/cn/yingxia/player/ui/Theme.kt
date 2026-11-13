package cn.yingxia.player.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cn.yingxia.player.core.ThemeMode

val BrandPink = Color(0xFFFB7299)
private val Light = lightColorScheme(
    primary = Color(0xFFC13765), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4ED), onPrimaryContainer = Color(0xFF802342),
    secondary = Color(0xFF99516A), secondaryContainer = Color(0xFFFFEDF3), onSecondaryContainer = Color(0xFF802342),
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF7F7F9), surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainerHigh = Color(0xFFF0F1F4), surfaceVariant = Color(0xFFF2F3F5),
    onBackground = Color(0xFF23252D), onSurface = Color(0xFF23252D),
    onSurfaceVariant = Color(0xFF5A5C66), outlineVariant = Color(0xFFE3E4EA))
private val Dark = darkColorScheme(
    primary = Color(0xFFFFA1BF), onPrimary = Color(0xFF5E1530),
    primaryContainer = Color(0xFF512333), onPrimaryContainer = Color(0xFFFFDFE9),
    secondary = Color(0xFFE8B1C5), secondaryContainer = Color(0xFF412A34), onSecondaryContainer = Color(0xFFFFDFE9),
    background = Color(0xFF111216), surface = Color(0xFF17181D),
    surfaceContainer = Color(0xFF202128), surfaceContainerLow = Color(0xFF191A20),
    surfaceContainerHigh = Color(0xFF292B33), surfaceVariant = Color(0xFF252630),
    onBackground = Color(0xFFF0EFF4), onSurface = Color(0xFFF0EFF4),
    onSurfaceVariant = Color(0xFFB5B5C1), outlineVariant = Color(0xFF32333C))

@Composable fun YingXiaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && isSystemInDarkTheme()
    val baseline = Typography()
    MaterialTheme(colorScheme = if (dark) Dark else Light,
        typography = baseline.copy(
            headlineLarge = baseline.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp),
            titleLarge = baseline.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            titleMedium = baseline.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            bodyMedium = baseline.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = baseline.labelSmall.copy(fontSize = 11.sp)), content = content)
}

fun <T> motionTween(durationMillis: Int = 220) = tween<T>(durationMillis, easing = FastOutSlowInEasing)

fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
fun fileSize(bytes: Long): String = if (bytes >= 1_073_741_824) "%.1f GB".format(bytes / 1_073_741_824.0) else "%.1f MB".format(bytes / 1_048_576.0)
fun speedText(speed: Float) = "${"%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')}×"
