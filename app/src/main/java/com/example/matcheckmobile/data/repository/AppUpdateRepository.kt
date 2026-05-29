package com.example.matcheckmobile.data.repository

import android.content.Context
import com.example.matcheckmobile.BuildConfig
import com.example.matcheckmobile.data.remote.update.AppUpdateFetcher
import com.example.matcheckmobile.data.remote.update.AppUpdateManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Состояние in-app updater'а — наблюдается главным экраном.
 *
 * Переходы:
 *   Idle → (start check) → Checking
 *     → fetch ok, нет апдейта → UpToDate
 *     → fetch ok, есть апдейт → Available
 *     → fetch failed → Failed
 *   Available → (user тапает «Установить») → Downloading
 *     → download ok → ReadyToInstall → автозапуск системного installer'а
 *     → download failed → Failed
 *   Available/Failed → (user тапает «Не сейчас» или закрывает) → Idle
 *
 * Состояние храним только in-memory: при cold start заново вызовется check()
 * из MatcheckApplication, и если апдейт всё ещё актуален — диалог появится
 * снова. Это упрощает реализацию и не требует DataStore-persistence.
 */
sealed class AppUpdateState {
    object Idle : AppUpdateState()
    object Checking : AppUpdateState()
    object UpToDate : AppUpdateState()
    data class Available(val manifest: AppUpdateManifest) : AppUpdateState()
    data class Downloading(val manifest: AppUpdateManifest, val percent: Int) : AppUpdateState()
    data class ReadyToInstall(val manifest: AppUpdateManifest, val apkFile: File) : AppUpdateState()
    data class Failed(val message: String) : AppUpdateState()
}

class AppUpdateRepository(
    private val appContext: Context,
    private val fetcher: AppUpdateFetcher,
    private val downloader: AppUpdateDownloader,
    private val installer: AppUpdateInstaller,
) {
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * В debug-сборке updater целиком выключен через BuildConfig.UPDATE_CHECK_ENABLED.
     * Тогда check() ничего не делает и state остаётся Idle — диалог не покажется
     * никогда, что и нужно для разработки.
     */
    fun checkForUpdate() {
        if (!BuildConfig.UPDATE_CHECK_ENABLED) return
        // Не дёргаем повторно, если уже идёт цикл (Checking/Downloading/ReadyToInstall).
        // Available/Failed заменим — пользователь возможно хочет повторить.
        when (_state.value) {
            is AppUpdateState.Checking,
            is AppUpdateState.Downloading,
            is AppUpdateState.ReadyToInstall -> return
            else -> Unit
        }
        scope.launch {
            _state.value = AppUpdateState.Checking
            val result = fetcher.fetch()
            _state.value = result.fold(
                onSuccess = { manifest ->
                    if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                        AppUpdateState.Available(manifest)
                    } else {
                        AppUpdateState.UpToDate
                    }
                },
                onFailure = { err ->
                    AppUpdateState.Failed(err.message ?: "Не удалось проверить обновление")
                },
            )
        }
    }

    /** Пользователь тапнул «Установить» в диалоге — качаем APK и запускаем installer. */
    fun startDownload(manifest: AppUpdateManifest) {
        scope.launch {
            _state.value = AppUpdateState.Downloading(manifest, percent = 0)
            val result = downloader.download(manifest) { percent ->
                _state.update { current ->
                    if (current is AppUpdateState.Downloading) current.copy(percent = percent)
                    else current
                }
            }
            _state.value = result.fold(
                onSuccess = { apk ->
                    // Auto-launch installer — пользователь только что нажал
                    // «Установить», лишний тап после прогресс-бара не нужен.
                    installer.launchInstaller(appContext, apk)
                    AppUpdateState.ReadyToInstall(manifest, apk)
                },
                onFailure = { err ->
                    AppUpdateState.Failed(err.message ?: "Не удалось скачать обновление")
                },
            )
        }
    }

    /** Пользователь нажал «Не сейчас» / закрыл диалог. */
    fun dismiss() {
        _state.value = AppUpdateState.Idle
    }
}
