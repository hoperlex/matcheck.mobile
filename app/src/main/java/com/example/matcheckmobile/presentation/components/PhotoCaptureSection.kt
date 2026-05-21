package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Секция «кнопка съёмки + лента превью». Используется на 1 Этапе и 2 Этапе:
 * кнопка с иконкой камеры и подписью, под ней — горизонтальный список миниатюр
 * сделанных снимков с тапом на превью и удалением.
 *
 * Поведение съёмки задаётся снаружи через [onTakePhoto] — это позволяет одной
 * и той же секции работать и как обычный photo capture, и как document scanner.
 */
@Composable
fun PhotoCaptureSection(
    buttonText: String,
    buttonTextStyle: TextStyle,
    isTablet: Boolean,
    buttonHeight: Dp,
    onTakePhoto: () -> Unit,
    photoPaths: List<String>,
    onRemovePhoto: (String) -> Unit,
    onPreviewPhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onTakePhoto,
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(if (isTablet) 40.dp else 32.dp),
                )
                Text(
                    text = buttonText,
                    style = buttonTextStyle,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (photoPaths.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(photoPaths, key = { it }) { path ->
                    PhotoThumb(
                        filePath = path,
                        onRemove = { onRemovePhoto(path) },
                        onClick = { onPreviewPhoto(path) },
                    )
                }
            }
        }
    }
}
