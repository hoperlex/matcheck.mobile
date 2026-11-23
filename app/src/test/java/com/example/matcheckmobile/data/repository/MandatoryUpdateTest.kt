package com.example.matcheckmobile.data.repository

import com.example.matcheckmobile.data.remote.update.AppUpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Обязательное обновление (minSupportedVersionCode): без «Не сейчас», и сбой
 * скачивания не выпускает инспектора обратно в старую сборку.
 */
class MandatoryUpdateTest {

    private fun manifest(versionCode: Int = 42, minSupported: Int = 1) = AppUpdateManifest(
        versionCode = versionCode,
        versionName = "1.0.41",
        apkUrl = "https://example.invalid/app.apk",
        minSupportedVersionCode = minSupported,
    )

    @Test
    fun `ниже минимальной версии — обязательно, на минимальной и выше — нет`() {
        assertTrue(isMandatoryUpdate(41, manifest(minSupported = 42)))
        assertFalse(isMandatoryUpdate(42, manifest(minSupported = 42)))
        assertFalse(isMandatoryUpdate(41, manifest(minSupported = 1)))
    }

    @Test
    fun `минимальная версия выше опубликованной — не запираем без APK`() {
        assertFalse(isMandatoryUpdate(41, manifest(versionCode = 41, minSupported = 50)))
    }

    @Test
    fun `обязательное обновление не закрывается, обычное — прячется в чип`() {
        val mandatory = AppUpdateState.Available(manifest(minSupported = 42), dialogShown = true, mandatory = true)
        assertEquals(mandatory, afterDismiss(mandatory, 41))

        val optional = AppUpdateState.Available(manifest(), dialogShown = true)
        assertEquals(optional.copy(dialogShown = false), afterDismiss(optional, 41))
    }

    @Test
    fun `сбой скачивания обязательного — снова диалог «Установить», обычного — тишина`() {
        val m = manifest(minSupported = 42)
        assertEquals(
            AppUpdateState.Available(m, mandatory = true),
            afterDismiss(AppUpdateState.DownloadFailed("сеть", m), 41),
        )
        assertEquals(
            AppUpdateState.Available(m, mandatory = true),
            afterDismiss(AppUpdateState.InstallBlocked(m, File("/tmp/x.apk")), 41),
        )
        assertEquals(AppUpdateState.Idle, afterDismiss(AppUpdateState.DownloadFailed("сеть", manifest()), 41))
    }
}
