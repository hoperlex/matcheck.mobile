package com.example.matcheckmobile

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.matcheckmobile.presentation.components.AppUpdateDialogHost
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
        // Проверка обновления при каждом возврате на передний план.
        // Periodic Worker (15 мин) покрывает «приложение открыто весь день»,
        // а onResume — «свернул/открыл = мгновенно увидел диалог установки».
        // Сам checkForUpdate() идемпотентен — Repository глушит повторные
        // вызовы во время Checking/Downloading/ReadyToInstall.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                (application as? MatcheckApplication)?.container
                    ?.appUpdateRepository?.checkForUpdate()
            }
        })
        enableEdgeToEdge()
        setContent {
            MatcheckmobileTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    MatcheckNavHost()
                    // Глобальный overlay-диалог обновления приложения. Сам ничего
                    // не рисует, пока AppUpdateRepository.state не Available —
                    // тогда поверх любой страницы навигации всплывает AlertDialog.
                    AppUpdateDialogHost()
                }
            }
        }
    }
}
