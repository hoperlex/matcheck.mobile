package com.example.matcheckmobile.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Брендовый синий matcheck — взят с веб-портала (apps/web/src/App.tsx
// ConfigProvider token colorPrimary = '#1677ff' — Ant Design blue-6).
// На мобайле как primary `#1677ff` смотрелся слишком ярким относительно
// планшетной Material You палитры, к которой пользователь привык. Взяли
// blue-8 (`#0958D9`) — тот же бренд-синий, но темнее и спокойнее. Контейнер
// и onContainer оставили из тех же blue-2/blue-9.
val MatcheckBlue = Color(0xFF0958D9)
val MatcheckBlueContainer = Color(0xFFD6E4FF)   // Ant Design blue-2
val MatcheckBlueOnContainer = Color(0xFF002C8C) // Ant Design blue-9