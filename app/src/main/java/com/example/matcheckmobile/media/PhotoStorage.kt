package com.example.matcheckmobile.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoStorage(private val context: Context) {
    private val photosDir: File
        get() = File(context.filesDir, "operation_photos").apply { if (!exists()) mkdirs() }

    fun createTempFile(prefix: String = "op"): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(photosDir, "${prefix}_${stamp}.jpg")
    }

    fun toContentUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file,
    )
}
