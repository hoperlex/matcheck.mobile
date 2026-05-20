package com.example.matcheckmobile

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.matcheckmobile.presentation.navigation.MatcheckNavHost
import com.example.matcheckmobile.ui.theme.MatcheckmobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // На Android 16 (targetSdk=36) система может игнорировать
        // screenOrientation из манифеста на планшетах. Используем NOSENSOR —
        // система перестаёт опрашивать гироскоп для смены ориентации совсем,
        // окно остаётся в portrait по умолчанию.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        // Не даём экрану гаснуть, пока приложение на переднем плане — инспектор
        // на КПП работает в режиме «всё время видно». При уходе в фон флаг
        // автоматически перестаёт действовать.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            MatcheckmobileTheme {
                MatcheckNavHost()
            }
        }
    }
}
