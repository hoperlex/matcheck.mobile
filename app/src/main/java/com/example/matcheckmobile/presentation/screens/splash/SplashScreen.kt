package com.example.matcheckmobile.presentation.screens.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.repository.AuthRepository

/**
 * Точка входа: пока решаем, куда вести (MAIN/LOGIN), показываем splash.
 *
 * Логика:
 *  - Нет refresh-token → LOGIN.
 *  - Есть refresh → proactive /auth/refresh:
 *      - Ok → MAIN (свежий access; не будет 401 на первом же запросе).
 *      - Invalid (сервер ответил 401) → LOGIN, токены уже стёрты в repo.
 *      - NetworkError → MAIN с тем что есть; TokenAuthenticator подхватит
 *        первое 401 когда сеть появится.
 *
 * Цель — убрать сценарий «открыл приложение → MAIN → первый sync вернул 401 →
 * редирект на LOGIN», который выглядит как разлогин для пользователя.
 */
@Composable
fun SplashScreen(
    onAuthenticated: () -> Unit,
    onUnauthenticated: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as MatcheckApplication).container

    LaunchedEffect(Unit) {
        if (!container.tokenStorage.isAuthenticated()) {
            onUnauthenticated()
            return@LaunchedEffect
        }
        when (container.authRepository.refreshNow()) {
            AuthRepository.RefreshOutcome.Ok,
            AuthRepository.RefreshOutcome.NetworkError -> onAuthenticated()
            AuthRepository.RefreshOutcome.Invalid,
            AuthRepository.RefreshOutcome.NoRefresh -> onUnauthenticated()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "matcheck",
                style = MaterialTheme.typography.headlineMedium,
            )
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
