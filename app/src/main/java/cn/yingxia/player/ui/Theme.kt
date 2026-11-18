package cn.yingxia.player.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yingxia.player.core.ThemeMode

val BrandPink = Color(0xFFE84870)
val BrandPinkDeep = Color(0xFFD0345C)
val BrandViolet = Color(0xFF7C5CFA)
val BrandAmber = Color(0xFFF59E0B)
val BrandTeal = Color(0xFF10B981)
val BrandBlue = Color(0xFF3B82F6)

private val Light = lightColorScheme(
    primary = Color(0xFFE03E68), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEEF2), onPrimaryContainer = Color(0xFF7A1836),
    secondary = Color(0xFF64687A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2F3F7), onSecondaryContainer = Color(0xFF2B2E3B),
    tertiary = Color(0xFF6C5CE7), tertiaryContainer = Color(0xFFF1EFFF), onTertiaryContainer = Color(0xFF2D1F7A),
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA), surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFEBEEF3), surfaceContainerHighest = Color(0xFFE2E6ED),
    surfaceVariant = Color(0xFFF1F2F6),
    onBackground = Color(0xFF181A20), onSurface = Color(0xFF181A20),
    onSurfaceVariant = Color(0xFF787C8C), outline = Color(0xFFC5C9D6), outlineVariant = Color(0xFFECEEF3),
    error = Color(0xFFE13D53), errorContainer = Color(0xFFFFE8EB), onErrorContainer = Color(0xFF7A1020))

private val Dark = darkColorScheme(
    primary = Color(0xFFFF7AA2), onPrimary = Color(0xFF4F0922),
    primaryContainer = Color(0xFF3A1826), onPrimaryContainer = Color(0xFFFFDCE6),
    secondary = Color(0xFFB8BCCF), onSecondary = Color(0xFF282B38),
    secondaryContainer = Color(0xFF232632), onSecondaryContainer = Color(0xFFE2E5F2),
    tertiary = Color(0xFFBDB2FF), tertiaryContainer = Color(0xFF2E2659), onTertiaryContainer = Color(0xFFE6E0FF),
    background = Color(0xFF0F1116), surface = Color(0xFF0F1116),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF161921), surfaceContainer = Color(0xFF1C202A),
    surfaceContainerHigh = Color(0xFF242936), surfaceContainerHighest = Color(0xFF2D3342),
    surfaceVariant = Color(0xFF202430),
    onBackground = Color(0xFFF1F2F7), onSurface = Color(0xFFF1F2F7),
    onSurfaceVariant = Color(0xFF8E93A6), outline = Color(0xFF4E5366), outlineVariant = Color(0xFF222632))

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp))

@Composable fun YingXiaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && isSystemInDarkTheme()
    val baseline = Typography()
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes,
        typography = baseline.copy(
            headlineLarge = baseline.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp),
            headlineSmall = baseline.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 21.sp),
            titleLarge = baseline.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
            titleMedium = baseline.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
            titleSmall = baseline.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
            bodyMedium = baseline.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = baseline.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            labelMedium = baseline.labelMedium.copy(fontWeight = FontWeight.Medium),
            labelSmall = baseline.labelSmall.copy(fontSize = 11.sp)), content = content)
}

/** 轻量无边框分组容器。 */
@Composable fun SectionCard(modifier: Modifier = Modifier, padding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** 柔和圆角图标底衬。 */
@Composable fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer, tint: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier.size(size), shape = RoundedCornerShape(size * .32f), color = container) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * .52f)) }
    }
}

fun <T> motionTween(durationMillis: Int = 220) = tween<T>(durationMillis, easing = FastOutSlowInEasing)

fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
fun fileSize(bytes: Long): String = if (bytes >= 1_073_741_824) "%.1f GB".format(bytes / 1_073_741_824.0) else "%.1f MB".format(bytes / 1_048_576.0)
fun speedText(speed: Float) = "${"%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')}×"
