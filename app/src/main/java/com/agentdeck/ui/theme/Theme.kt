package com.agentdeck.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BlueContainer,
    onPrimaryContainer = BlueOnContainer,
    secondary = RedPrimary,
    onSecondary = RedOnPrimary,
    secondaryContainer = RedContainer,
    onSecondaryContainer = RedOnContainer,
    background = NeutralSurface,
    onBackground = NeutralOnSurface,
    surface = NeutralSurface,
    onSurface = NeutralOnSurface,
    surfaceVariant = NeutralSurfaceContainerHigh,
    onSurfaceVariant = NeutralOnSurfaceVariant,
    surfaceContainer = NeutralSurfaceContainer,
    surfaceContainerHigh = NeutralSurfaceContainerHigh,
    outline = NeutralOutline,
    error = ErrorDark,
    onError = OnErrorDark,
)

private val LightColors = lightColorScheme(
    primary = BluePrimaryLight,
    onPrimary = BlueOnPrimaryLight,
    primaryContainer = BlueContainerLight,
    onPrimaryContainer = BlueOnContainerLight,
    secondary = RedPrimaryLight,
    onSecondary = RedOnPrimaryLight,
    secondaryContainer = RedContainerLight,
    onSecondaryContainer = RedOnContainerLight,
    background = NeutralSurfaceLight,
    onBackground = NeutralOnSurfaceLight,
    surface = NeutralSurfaceLight,
    onSurface = NeutralOnSurfaceLight,
    surfaceVariant = NeutralSurfaceContainerHighLight,
    onSurfaceVariant = NeutralOnSurfaceVariantLight,
    surfaceContainer = NeutralSurfaceContainerLight,
    surfaceContainerHigh = NeutralSurfaceContainerHighLight,
    outline = NeutralOutlineLight,
    error = ErrorLight,
    onError = OnErrorLight,
)

/**
 * App typography.
 *
 * Weight and spacing are slightly tightened for the dense card grid.
 */
private val AgentTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

/**
 * Applies the AgentDeck theme.
 *
 * Dynamic colour is used on Android 12+ when available, but the site accent
 * colours (#4D6BFE / #D9433B) are fixed by design because they carry the
 * CN / VPN meaning, so they are never derived from the wallpaper.
 */
@Composable
fun AgentDeckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AgentTypography,
        content = content,
    )
}
