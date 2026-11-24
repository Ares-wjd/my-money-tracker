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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mymoneytracker.app.R

/** 브랜드 색 (스톤 테마 v2.18, docs/FEATURES.md 6. 디자인). */
object Brand {
    val Green = Color(0xFF0E7565)
    val Ink = Color(0xFF1A1D21)
    val Ground = Color(0xFFF1F2F3)
    val Gold = Color(0xFFB07D12)
    val Mint = Color(0xFF52CDAE)
}

/** 금액·수익률 표시에 쓰는 색. 수익은 빨강, 손실은 파랑 (국내 증권 앱 관례). */
@Immutable
data class MoneyColors(
    val profit: Color,
    val loss: Color,
    /** 요약 카드(총 평가금 등): 카드와 같은 바탕에 진한 테두리 (v2.18). */
    val heroContainer: Color,
    val onHero: Color,
    val heroLabel: Color,
    val heroBorder: Color,
    /** 목표 진행 막대. */
    val goal: Color,
    val goalDone: Color,
    /** 금색 (로고 점·투자금 점선). */
    val gold: Color,
)

private val LightMoney = MoneyColors(
    profit = Color(0xFFC9252F),
    loss = Color(0xFF1F55C4),
    heroContainer = Color.White,
    onHero = Brand.Ink,
    heroLabel = Color(0xFF555C63),
    heroBorder = Brand.Ink,
    goal = Brand.Green,
    goalDone = Brand.Gold,
    gold = Brand.Gold,
)

private val DarkMoney = MoneyColors(
    profit = Color(0xFFFF7B86),
    loss = Color(0xFF82A9FF),
    heroContainer = Color(0xFF1A1D21),
    onHero = Color(0xFFE7E9EB),
    heroLabel = Color(0xFFA3ABB3),
    heroBorder = Color(0xFF5A626B),
    goal = Brand.Mint,
    goalDone = Color(0xFFD9B15A),
    gold = Color(0xFFD9B15A),
)

val LocalMoneyColors = staticCompositionLocalOf { LightMoney }

/** 지금 어두운 화면인지 (화면 모드 설정 v2.18: 고르면 그 값, 아니면 폰 설정). 폰 설정을 직접 보지 말고 이 값을 쓴다. */
val LocalDarkTheme = staticCompositionLocalOf { false }

private val LightColors: ColorScheme = lightColorScheme(
    primary = Brand.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2F0EB),
    onPrimaryContainer = Color(0xFF0A5A4D),
    secondary = Brand.Ink,
    onSecondary = Color.White,
    // 하단 메뉴 현재 탭·선택 칩: 연한 포인트 바탕 + 진녹 글자
    secondaryContainer = Color(0xFFE2F0EB),
    onSecondaryContainer = Color(0xFF0A5A4D),
    tertiary = Brand.Gold,
    onTertiary = Color.White,
    background = Brand.Ground,
    onBackground = Brand.Ink,
    // 화면 바탕(앱 바 포함)은 옅은 회색, 카드는 흰색(surfaceContainerLowest).
    surface = Brand.Ground,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFEBEDEF),
    onSurfaceVariant = Color(0xFF555C63),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8F9),
    surfaceContainer = Color(0xFFEBEDEF),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFDDE1E4),
    inverseSurface = Brand.Ink,
    inverseOnSurface = Color.White,
    outline = Color(0xFF8A9096),
    outlineVariant = Color(0xFFDDE1E4),
    error = Color(0xFFB3261E),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Brand.Mint,
    onPrimary = Color(0xFF08231C),
    primaryContainer = Color(0xFF183A33),
    onPrimaryContainer = Color(0xFF7FE0C6),
    secondary = Color(0xFFE7E9EB),
    onSecondary = Color(0xFF121417),
    secondaryContainer = Color(0xFF183A33),
    onSecondaryContainer = Color(0xFF7FE0C6),
    tertiary = Color(0xFFD9B15A),
    onTertiary = Color(0xFF2A1F00),
    background = Color(0xFF121417),
    onBackground = Color(0xFFE7E9EB),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE7E9EB),
    surfaceVariant = Color(0xFF23272C),
    onSurfaceVariant = Color(0xFFA3ABB3),
    // 카드(surfaceContainerLowest)를 바탕보다 밝게 해 구분한다.
    surfaceContainerLowest = Color(0xFF1A1D21),
    surfaceContainerLow = Color(0xFF1D2125),
    surfaceContainer = Color(0xFF23272C),
    surfaceContainerHigh = Color(0xFF2A2F35),
    surfaceContainerHighest = Color(0xFF2E3338),
    inverseSurface = Color(0xFFE7E9EB),
    inverseOnSurface = Color(0xFF121417),
    outline = Color(0xFF6E7780),
    outlineVariant = Color(0xFF2E3338),
    error = Color(0xFFFFB4AB),
)

val PlexSansKr = FontFamily(
    Font(R.font.ibm_plex_sans_kr_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_kr_semibold, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_kr_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_kr_bold, FontWeight.Bold),
)

/** 큰 금액·화면 제목·카드 제목 (v2.18). 자주 쓰는 한글 2,350자 + 영문·숫자만 들어 있다 (없는 글자는 본문 글꼴로). */
val SerifKr = FontFamily(Font(R.font.noto_serif_kr_bold, FontWeight.Bold))

/** 모든 글자에 IBM Plex Sans KR, 숫자는 자릿수를 맞춘다(tnum). */
private fun TextStyle.branded() = copy(fontFamily = PlexSansKr, fontFeatureSettings = "tnum")

/** 큰 금액·제목: Noto Serif KR Bold. */
private fun TextStyle.serif() = copy(fontFamily = SerifKr, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.branded(),
        displayMedium = t.displayMedium.branded(),
        displaySmall = t.displaySmall.branded(),
        headlineLarge = t.headlineLarge.serif(),
        headlineMedium = t.headlineMedium.serif(),
        headlineSmall = t.headlineSmall.serif(),
        titleLarge = t.titleLarge.serif(),
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

/** 모서리 (v2.18): 칩·선택 막대 10, 버튼·입력칸 12, 카드 16. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/**
 * 화면 모드 (v2.18): [darkTheme] 는 고른 값 (고르기 전에는 폰 설정). 브랜드 색을 유지하려고 배경화면 기반 색(dynamic color)은 쓰지 않는다.
 */
@Composable
fun MoneyTrackerTheme(
    darkTheme: Boolean = ThemeMode.chosen.collectAsState().value ?: isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMoneyColors provides if (darkTheme) DarkMoney else LightMoney,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
