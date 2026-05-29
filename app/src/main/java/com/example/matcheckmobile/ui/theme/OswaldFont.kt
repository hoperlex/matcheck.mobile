package com.example.matcheckmobile.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.example.matcheckmobile.R

/**
 * FontFamily для Google Fonts «Oswald» — узкий заголовочный шрифт. Используется
 * для брендовой надписи «su10» в шапке main-экрана. Скачивается тем же GMS-
 * провайдером, что и Open Sans (см. OpenSansFont.kt); при недоступности
 * сети / Play Services Compose откатывается на системный sans-serif.
 */
private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val OswaldName = GoogleFont("Oswald")

val OswaldFontFamily: FontFamily = FontFamily(
    Font(googleFont = OswaldName, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(googleFont = OswaldName, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(googleFont = OswaldName, fontProvider = GoogleFontsProvider, weight = FontWeight.Bold, style = FontStyle.Normal),
)
