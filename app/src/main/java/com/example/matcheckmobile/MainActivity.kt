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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.matcheckmobile.presentation.components.AppUpdateDialogHost
import com.example.matcheckmobile.presentation.navigation.MatcheckNavHost
import com.example.matcheckmobile.sync.MatcheckSyncScheduler
import com.example.matcheckmobile.ui.theme.MatcheckmobileTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initial-значение до того, как DataStore прочитает prefersLandscape.
        // Дальше реактивный collect перестроит ориентацию из preference.
        // Внимание: на Android 16 для targetSdk≥36 система может игнорировать
        // requestedOrientation на больших экранах sw600dp+ (Samsung-планшет и т.п.),
        // см. https://developer.android.com/about/versions/16/behavior-changes-16.
        // Сейчас targetSdk=35 — флаг ещё работает; при подъёме targetSdk нужно
        // переключаться на адаптив через WindowSizeClass и физический поворот.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        applyOrientationPreference()
        // Не даём экрану гаснуть, пока приложение на переднем плане — инспектор
        // на КПП работает в режиме «всё время видно». При уходе в фон флаг
        // автоматически перестаёт действовать.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Проверка обновления + триггер sync при каждом возврате на передний план.
        //
        // checkForUpdate: Periodic Worker (15 мин) покрывает «приложение открыто
        // весь день», а onResume — «свернул/открыл = мгновенно увидел диалог
        // установки». Сам checkForUpdate() идемпотентен — Repository глушит
        // повторные вызовы во время Checking/Downloading/ReadyToInstall.
        //
        // requestImmediateSync: для мульти-планшетного сценария (несколько
        // инспекторов на одном логине). SSE-канал в фоне может отвалиться
        // (Android агрессивно убивает background TCP), а Periodic Worker
        // крутится с шагом 15 мин — слишком долго ждать «свежак». Возврат
        // в app = моментальный sync; WorkManager дедупит частые вызовы
        // через ExistingWorkPolicy.REPLACE, поэтому штормить не будет.
        // Полностью аддитивно — данные не трогаются, локальные мутации не
        // пересоздаются (см. SyncRepository.syncOnce).
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                val container = (application as? MatcheckApplication)?.container
                container?.appUpdateRepository?.checkForUpdate()
                MatcheckSyncScheduler.requestImmediateSync(this@MainActivity)
                // Возврат в foreground: SSE-канал в фоне мог быть убит системой
                // (Doze/agressive battery). Будим его, чтобы снова шли real-time
                // события; если уже на связи — wake() это no-op.
                container?.sseConnectionManager?.wake()
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

    /**
     * Подписка на prefersLandscape из DataStore. При переключении тумблера
     * на главной мгновенно меняем requestedOrientation: SENSOR_LANDSCAPE
     * (любая landscape по гироскопу) ↔ NOSENSOR (фиксируем portrait, гироскоп
     * отключён — раньше так было прибито в манифесте и в onCreate).
     *
     * На больших экранах с Android 16 и targetSdk≥36 система может проигнорировать
     * это требование и оставить ориентацию по физическому положению устройства —
     * это ожидаемое поведение платформы, не баг приложения.
     */
    private fun applyOrientationPreference() {
        val container = (application as? MatcheckApplication)?.container ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.deviceSettings.prefersLandscapeFlow
                    .distinctUntilChanged()
                    .collect { wantsLandscape ->
                        val next = if (wantsLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                        }
                        if (requestedOrientation != next) {
                            requestedOrientation = next
                        }
                    }
            }
        }
    }
}
