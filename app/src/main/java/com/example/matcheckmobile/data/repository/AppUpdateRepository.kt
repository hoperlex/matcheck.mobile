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
    // dialogShown=true → AlertDialog «Доступно обновление» висит поверх главного
    // экрана; false → диалог скрыт, но в шапке остаётся chip «Установить
    // обновление», по тапу инспектор откроет диалог обратно или сразу запустит
    // скачивание. Установка флага происходит из dismiss() (тап «Не сейчас») —
    // сама запись Available остаётся в state.
    data class Available(
        val manifest: AppUpdateManifest,
        val dialogShown: Boolean = true,
    ) : AppUpdateState()
    data class Downloading(val manifest: AppUpdateManifest, val percent: Int) : AppUpdateState()
    data class ReadyToInstall(val manifest: AppUpdateManifest, val apkFile: File) : AppUpdateState()
    // APK скачан, но система не даёт запустить установку — нет разрешения
    // «Установка неизвестных приложений» для su10. Установщик уже открыл
    // нужный системный экран; пользователь включает тумблер и жмёт «Установить»
    // (installNow) — повторного скачивания не требуется, apkFile уже на диске.
    data class InstallBlocked(val manifest: AppUpdateManifest, val apkFile: File) : AppUpdateState()
    // Ошибка СКАЧИВАНИЯ/запуска установки (user-initiated, в отличие от тихого
    // Failed фоновой проверки). Показывается всегда: пользователь сам нажал
    // «Установить» и ждёт результат. manifest хранится, чтобы «Повторить»
    // перекачало без новой проверки версий.
    data class DownloadFailed(val message: String, val manifest: AppUpdateManifest) : AppUpdateState()
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
                onSuccess = { apk -> proceedToInstall(manifest, apk) },
                onFailure = { err ->
                    AppUpdateState.DownloadFailed(
                        err.message ?: "Не удалось скачать обновление",
                        manifest,
                    )
                },
            )
        }
    }

    /**
     * APK скачан, пробуем установить. Если нет разрешения «Установка
     * неизвестных приложений» — открываем системный экран и встаём в
     * InstallBlocked (пользователь включит и нажмёт «Установить» ещё раз,
     * уже без скачивания). Если запуск установщика упал — DownloadFailed
     * с видимым сообщением, а не молчаливый проброс.
     */
    private fun proceedToInstall(
        manifest: AppUpdateManifest,
        apk: File,
    ): AppUpdateState {
        if (!installer.canInstall(appContext)) {
            installer.openUnknownSourcesSettings(appContext)
            return AppUpdateState.InstallBlocked(manifest, apk)
        }
        return installer.launchInstaller(appContext, apk).fold(
            onSuccess = { AppUpdateState.ReadyToInstall(manifest, apk) },
            onFailure = { err ->
                AppUpdateState.DownloadFailed(
                    err.message ?: "Не удалось запустить установку",
                    manifest,
                )
            },
        )
    }

    /**
     * Повторный запуск установки уже скачанного APK — из InstallBlocked после
     * того, как пользователь включил разрешение. Если так и не включил —
     * снова InstallBlocked (повторно откроем настройки).
     */
    fun installNow(manifest: AppUpdateManifest, apkFile: File) {
        scope.launch { _state.value = proceedToInstall(manifest, apkFile) }
    }

    /** Открыть системный экран «Установка неизвестных приложений» для su10. */
    fun openInstallSettings() {
        installer.openUnknownSourcesSettings(appContext)
    }

    /**
     * Пользователь нажал «Не сейчас» в AlertDialog. Состояние Available
     * сохраняется (chip «Установить обновление» в шапке остаётся видимым),
     * но dialogShown=false — поверх главного экрана модального окна нет.
     * Для других состояний (Failed / UpToDate / Checking) — обычный сброс
     * в Idle, чтобы chip пропал.
     */
    fun dismiss() {
        _state.update { current ->
            when (current) {
                is AppUpdateState.Available -> current.copy(dialogShown = false)
                else -> AppUpdateState.Idle
            }
        }
    }

    /**
     * Тап на chip «Установить обновление» в шапке — снова показываем
     * AlertDialog (если был закрыт через «Не сейчас»). Если state не
     * Available — ничего не делаем.
     */
    fun reopenDialog() {
        _state.update { current ->
            if (current is AppUpdateState.Available) current.copy(dialogShown = true)
            else current
        }
    }
}
