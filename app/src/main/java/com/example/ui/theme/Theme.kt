package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.compose.runtime.collectAsState

private val DarkColorScheme =
  darkColorScheme(
    primary = WaterBlue,
    onPrimary = Color(0xFF000000),
    secondary = WaterBlueAccent,
    background = DeepSlate,
    surface = Charcoal,
    surfaceVariant = SurfaceCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextPrimary
  )

@Composable
fun MyApplicationTheme(
  // Force Ultra-Dark mode as requested
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val sduiPrefs = com.example.api.RemoteConfigManager.sduiPreferences.collectAsState().value
  val colorScheme = if (sduiPrefs.defaultThemeOverride == "SLATE_DARK") {
    darkColorScheme(
      primary = Color(0xFF38BDF8),
      onPrimary = Color.Black,
      secondary = Color(0xFF0EA5E9),
      background = Color(0xFF0B0F19),
      surface = Color(0xFF1E293B),
      surfaceVariant = Color(0xFF334155),
      onBackground = Color(0xFFF1F5F9),
      onSurface = Color(0xFFF1F5F9),
      onSurfaceVariant = Color(0xFF94A3B8)
    )
  } else {
    DarkColorScheme
  }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
