package com.example.matcheckmobile.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Получает «свежую» координату для проставления в водяной знак на фото.
 *
 * Используется FusedLocationProviderClient.lastLocation — это уже агрегированная
 * последняя позиция, без свежего GPS-fix. Для нашей задачи (штамп на фото)
 * этого хватает: цена fresh-fix — секунды ожидания и греющийся GPS.
 *
 * При отсутствии разрешения или сигнала возвращает null — клиенту вызывающему
 * нужно отрисовать «GPS: нет».
 */
class LocationProvider(private val context: Context) {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchCurrent(): Coords? {
        if (!hasPermission()) return null
        return suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { loc ->
                    val result = loc?.let { Coords(it.latitude, it.longitude) }
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    data class Coords(val lat: Double, val lon: Double)
}
