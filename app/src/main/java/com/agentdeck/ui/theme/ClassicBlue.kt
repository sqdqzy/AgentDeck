package com.agentdeck.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The "classic blue" palette used by the error window.
 *
 * The error window deliberately does **not** follow the wallpaper-based
 * Material You palette: when something goes wrong the interface has to look
 * the same on every device so the log stays readable. Light and dark are
 * still honoured, they are simply two fixed classic-blue schemes instead of
 * one dynamically derived scheme.
 */

// Dark classic blue
val ClassicBluePrimaryDark = Color(0xFFA8C8FF)
val ClassicBlueOnPrimaryDark = Color(0xFF00315C)
val ClassicBlueContainerDark = Color(0xFF004787)
val ClassicBlueOnContainerDark = Color(0xFFD6E4FF)
val ClassicBlueSurfaceDark = Color(0xFF111318)
val ClassicBlueSurfaceContainerDark = Color(0xFF1E2025)
val ClassicBlueSurfaceContainerHighDark = Color(0xFF282A30)
val ClassicBlueOnSurfaceDark = Color(0xFFE3E2E6)
val ClassicBlueOnSurfaceVariantDark = Color(0xFFC4C6D0)
val ClassicBlueOutlineDark = Color(0xFF8E9099)
val ClassicBlueErrorDark = Color(0xFFFFB4AB)
val ClassicBlueOnErrorDark = Color(0xFF690005)

// Light classic blue
val ClassicBluePrimaryLight = Color(0xFF1565C0)
val ClassicBlueOnPrimaryLight = Color(0xFFFFFFFF)
val ClassicBlueContainerLight = Color(0xFFD6E4FF)
val ClassicBlueOnContainerLight = Color(0xFF001B3F)
val ClassicBlueSurfaceLight = Color(0xFFFCFCFF)
val ClassicBlueSurfaceContainerLight = Color(0xFFF1F1F6)
val ClassicBlueSurfaceContainerHighLight = Color(0xFFEBEBF0)
val ClassicBlueOnSurfaceLight = Color(0xFF1A1B20)
val ClassicBlueOnSurfaceVariantLight = Color(0xFF44474F)
val ClassicBlueOutlineLight = Color(0xFF75777F)
val ClassicBlueErrorLight = Color(0xFFBA1A1A)
val ClassicBlueOnErrorLight = Color(0xFFFFFFFF)

/** The classic blue scheme used on dark surfaces. */
val ClassicBlueDarkColors = darkColorScheme(
    primary = ClassicBluePrimaryDark,
    onPrimary = ClassicBlueOnPrimaryDark,
    primaryContainer = ClassicBlueContainerDark,
    onPrimaryContainer = ClassicBlueOnContainerDark,
    secondary = ClassicBluePrimaryDark,
    onSecondary = ClassicBlueOnPrimaryDark,
    secondaryContainer = ClassicBlueContainerDark,
    onSecondaryContainer = ClassicBlueOnContainerDark,
    background = ClassicBlueSurfaceDark,
    onBackground = ClassicBlueOnSurfaceDark,
    surface = ClassicBlueSurfaceDark,
    onSurface = ClassicBlueOnSurfaceDark,
    surfaceVariant = ClassicBlueSurfaceContainerHighDark,
    onSurfaceVariant = ClassicBlueOnSurfaceVariantDark,
    surfaceContainer = ClassicBlueSurfaceContainerDark,
    surfaceContainerHigh = ClassicBlueSurfaceContainerHighDark,
    outline = ClassicBlueOutlineDark,
    error = ClassicBlueErrorDark,
    onError = ClassicBlueOnErrorDark,
)

/** The classic blue scheme used on light surfaces. */
val ClassicBlueLightColors = lightColorScheme(
    primary = ClassicBluePrimaryLight,
    onPrimary = ClassicBlueOnPrimaryLight,
    primaryContainer = ClassicBlueContainerLight,
    onPrimaryContainer = ClassicBlueOnContainerLight,
    secondary = ClassicBluePrimaryLight,
    onSecondary = ClassicBlueOnPrimaryLight,
    secondaryContainer = ClassicBlueContainerLight,
    onSecondaryContainer = ClassicBlueOnContainerLight,
    background = ClassicBlueSurfaceLight,
    onBackground = ClassicBlueOnSurfaceLight,
    surface = ClassicBlueSurfaceLight,
    onSurface = ClassicBlueOnSurfaceLight,
    surfaceVariant = ClassicBlueSurfaceContainerHighLight,
    onSurfaceVariant = ClassicBlueOnSurfaceVariantLight,
    surfaceContainer = ClassicBlueSurfaceContainerLight,
    surfaceContainerHigh = ClassicBlueSurfaceContainerHighLight,
    outline = ClassicBlueOutlineLight,
    error = ClassicBlueErrorLight,
    onError = ClassicBlueOnErrorLight,
)
