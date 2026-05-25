package com.example.matcheckmobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

// Светлая брендовая схема matcheck. primary = синий с веб-портала (#1677FF),
// контейнеры — палитра Ant Design blue. Остальные роли (surface, background,
// outline) оставляем дефолтными — Material 3 сам выводит нейтральные
// светлые тона. Так UI везде получает одинаковый синий look, синхронный с
// тем, что инспектор видит на web/Ant Design.
private val LightColorScheme = lightColorScheme(
    primary = MatcheckBlue,
    onPrimary = Color.White,
    primaryContainer = MatcheckBlueContainer,
    onPrimaryContainer = MatcheckBlueOnContainer,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

@Composable
fun MatcheckmobileTheme(
    // Раньше тема следовала системному dark mode + Material You dynamic
    // colors — из-за этого на Realme/Oppo (Android 14/15) в тёмной системной
    // теме UI становился «всё близко к чёрному», а от устройства к устройству
    // палитра разнилась. Зафиксировали светлую брендовую схему, dynamic
    // выключен. darkTheme/dynamicColor оставлены параметрами на случай, если
    // когда-то решим вернуть автоподстройку под систему.
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}