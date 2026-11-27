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

// YingXia 2.0 Minimalist Palette: Crisp Monochrome + Calm Slate Blue Accent + Vault Emerald
val SlateAccent = Color(0xFF2563EB)
val SlateAccentDark = Color(0xFF60A5FA)
val VaultEmerald = Color(0xFF059669)
val VaultEmeraldBright = Color(0xFF34D399)
val VaultEmeraldContainerDark = Color(0xFF064E3B)
val ObsidianSurface = Color(0xFF111318)
val CinemaDarkCanvas = Color(0xFF0B0D11)
val CinemaDarkSurface = Color(0xFF151922)

// Legacy token aliases mapped to calm 2.0 palette so secondary panels stay cohesive
val BrandPink = SlateAccent
val BrandPinkDeep = Color(0xFF1D4ED8)
val BrandViolet = VaultEmerald
val BrandAmber = Color(0xFFD97706)
val BrandTeal = VaultEmerald
val BrandBlue = SlateAccent

private val Light = lightColorScheme(
    primary = SlateAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF4FF),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3F4F6),
    onSecondaryContainer = Color(0xFF1F2937),
    tertiary = VaultEmerald,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECFDF5),
    onTertiaryContainer = Color(0xFF065F46),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6F8),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE5E7EB),
    surfaceContainerHighest = Color(0xFFD1D5DB),
    surfaceVariant = Color(0xFFF3F4F6),
    onBackground = Color(0xFF111318),
    onSurface = Color(0xFF111318),
    onSurfaceVariant = Color(0xFF68707E),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = Color(0xFF991B1B),
)

private val Dark = darkColorScheme(
    primary = SlateAccentDark,
    onPrimary = Color(0xFF0A192F),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF9CA3AF),
    onSecondary = Color(0xFF111827),
    secondaryContainer = Color(0xFF1F2430),
    onSecondaryContainer = Color(0xFFE5E7EB),
    tertiary = VaultEmeraldBright,
    onTertiary = Color(0xFF022C22),
    tertiaryContainer = VaultEmeraldContainerDark,
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = CinemaDarkCanvas,
    surface = CinemaDarkCanvas,
    surfaceContainerLowest = Color(0xFF06080B),
    surfaceContainerLow = CinemaDarkSurface,
    surfaceContainer = Color(0xFF1C212E),
    surfaceContainerHigh = Color(0xFF252B3B),
    surfaceContainerHighest = Color(0xFF30384C),
    surfaceVariant = Color(0xFF1A1F2B),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF8A92A3),
    outline = Color(0xFF374151),
    outlineVariant = Color(0xFF1F2430),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFECACA),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable fun YingXiaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && isSystemInDarkTheme()
    val baseline = Typography()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = AppShapes,
        typography = baseline.copy(
            headlineLarge = baseline.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 25.sp),
            headlineSmall = baseline.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
            titleLarge = baseline.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
            titleMedium = baseline.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
            titleSmall = baseline.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
            bodyMedium = baseline.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = baseline.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            labelMedium = baseline.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
            labelSmall = baseline.labelSmall.copy(fontSize = 11.sp),
        ),
        content = content,
    )
}

/** 轻量无边框分组容器。 */
@Composable fun SectionCard(modifier: Modifier = Modifier, padding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** 单色克制圆角图标底衬。 */
@Composable fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(modifier.size(size), shape = RoundedCornerShape(size * .28f), color = container) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * .5f)) }
    }
}

fun <T> motionTween(durationMillis: Int = 200) = tween<T>(durationMillis, easing = FastOutSlowInEasing)

fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
fun fileSize(bytes: Long): String = if (bytes >= 1_073_741_824) "%.1f GB".format(bytes / 1_073_741_824.0) else "%.1f MB".format(bytes / 1_048_576.0)
fun speedText(speed: Float) = "${"%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.')}×"
