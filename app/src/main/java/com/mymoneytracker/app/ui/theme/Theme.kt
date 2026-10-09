package com.mymoneytracker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.R

/** 브랜드 색 (디자인 시안 "Money Tracker"). */
object Brand {
    val Green = Color(0xFF0F6B5C)
    val Ink = Color(0xFF16201C)
    val Ground = Color(0xFFF4F5F2)
    val Gold = Color(0xFFF2B544)
    val Mint = Color(0xFF34B38A)
}

/** 금액·수익률 표시에 쓰는 색. 수익은 빨강, 손실은 파랑 (국내 증권 앱 관례). */
@Immutable
data class MoneyColors(
    val profit: Color,
    val loss: Color,
    /** 홈 평가금 카드처럼 강조 카드의 바탕과 글자. */
    val heroContainer: Color,
    val onHero: Color,
    /** 목표 진행 막대. */
    val goal: Color,
    val goalDone: Color,
)

private val LightMoney = MoneyColors(
    profit = Color(0xFFD6293E),
    loss = Color(0xFF1F5FD1),
    heroContainer = Brand.Green,
    onHero = Color.White,
    goal = Brand.Gold,
    goalDone = Brand.Green,
)

private val DarkMoney = MoneyColors(
    profit = Color(0xFFFF6B7A),
    loss = Color(0xFF7AA7FF),
    heroContainer = Color(0xFF134A3F),
    onHero = Color(0xFFE6F5EF),
    goal = Brand.Gold,
    goalDone = Color(0xFF5CC9A7),
)

val LocalMoneyColors = staticCompositionLocalOf { LightMoney }

private val LightColors: ColorScheme = lightColorScheme(
    primary = Brand.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F2EE),
    onPrimaryContainer = Color(0xFF0B3D33),
    secondary = Brand.Ink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E8E5),
    onSecondaryContainer = Brand.Ink,
    tertiary = Brand.Gold,
    onTertiary = Color(0xFF3B2A00),
    background = Brand.Ground,
    onBackground = Brand.Ink,
    // 화면 바탕(앱 바 포함)은 옅은 회색, 카드는 흰색(surfaceContainerLowest).
    surface = Brand.Ground,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFF0F2EF),
    onSurfaceVariant = Color(0xFF55655E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBF9),
    surfaceContainer = Color(0xFFF0F2EF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE4E8E5),
    inverseSurface = Brand.Ink,
    inverseOnSurface = Color.White,
    outline = Color(0xFF8E9A94),
    outlineVariant = Color(0xFFE4E8E5),
    error = Color(0xFFBA1A1A),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF5CC9A7),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF134A3F),
    onPrimaryContainer = Color(0xFFBDEFDF),
    secondary = Color(0xFFE3E8E5),
    onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFF2D3531),
    onSecondaryContainer = Color(0xFFE3E8E5),
    tertiary = Brand.Gold,
    onTertiary = Color(0xFF3B2A00),
    background = Color(0xFF0F1412),
    onBackground = Color(0xFFE3E8E5),
    surface = Color(0xFF0F1412),
    onSurface = Color(0xFFE3E8E5),
    surfaceVariant = Color(0xFF222A26),
    onSurfaceVariant = Color(0xFFA3B0AA),
    surfaceContainerLowest = Color(0xFF171D1A),
    surfaceContainerLow = Color(0xFF1A211E),
    surfaceContainer = Color(0xFF1E2522),
    surfaceContainerHigh = Color(0xFF252D29),
    surfaceContainerHighest = Color(0xFF2D3531),
    inverseSurface = Color(0xFFE3E8E5),
    inverseOnSurface = Brand.Ink,
    outline = Color(0xFF66736D),
    outlineVariant = Color(0xFF2C3531),
    error = Color(0xFFFFB4AB),
)

val PlexSansKr = FontFamily(
    Font(R.font.ibm_plex_sans_kr_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_kr_semibold, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_kr_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_kr_bold, FontWeight.Bold),
)

/** 모든 글자에 IBM Plex Sans KR, 숫자는 자릿수를 맞춘다(tnum). */
private fun TextStyle.branded() = copy(fontFamily = PlexSansKr, fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.branded(),
        displayMedium = t.displayMedium.branded(),
        displaySmall = t.displaySmall.branded(),
        headlineLarge = t.headlineLarge.branded().copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.branded().copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.branded().copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.branded().copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.branded().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.branded().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.branded(),
        bodyMedium = t.bodyMedium.branded(),
        bodySmall = t.bodySmall.branded(),
        labelLarge = t.labelLarge.branded().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.branded(),
        labelSmall = t.labelSmall.branded(),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 폰 설정(밝게/어둡게)을 따른다. 브랜드 색을 유지하려고 배경화면 기반 색(dynamic color)은 쓰지 않는다. */
@Composable
fun MoneyTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMoneyColors provides if (darkTheme) DarkMoney else LightMoney) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
