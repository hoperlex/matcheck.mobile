package com.example.matcheckmobile.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.example.matcheckmobile.R

/**
 * FontFamily для Google Fonts «Open Sans». Скачивается через GMS-провайдер
 * (com.google.android.gms.fonts) при первом использовании, кэшируется. Если
 * сеть/Play Services недоступны — Compose откатывается на системный sans-serif.
 */
private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val OpenSansName = GoogleFont("Open Sans")

val OpenSansFontFamily: FontFamily = FontFamily(
    Font(googleFont = OpenSansName, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(googleFont = OpenSansName, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(googleFont = OpenSansName, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(googleFont = OpenSansName, fontProvider = GoogleFontsProvider, weight = FontWeight.Bold, style = FontStyle.Normal),
)
