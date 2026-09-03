package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.example.matcheckmobile.ui.icons.LocalIcons

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
    // Превью получает не только путь к фото, но и колбэк удаления именно
    // для этого фото — экран открывает PhotoPreviewDialog с кнопкой
    // «Удалить», а X-крестик на миниатюре больше не рисуется.
    onPreviewPhoto: (path: String, onDelete: () -> Unit) -> Unit,
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
                    imageVector = LocalIcons.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(if (isTablet) 40.dp else 32.dp),
                )
                Text(
                    text = buttonText,
                    style = buttonTextStyle,
                    textAlign = TextAlign.Center,
                    // На узких экранах телефонов длинные подписи вроде
                    // «Фото груза, госномера» иначе уезжают на 3 строки и
                    // обрезаются высотой кнопки. Лимит в 2 строки + softWrap
                    // by default дают аккуратный перенос без обрезки слов.
                    maxLines = 2,
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
                        // onRemove не передаём — крестика на миниатюре нет.
                        // Удаление идёт через PhotoPreviewDialog с подтверждением.
                        onClick = { onPreviewPhoto(path) { onRemovePhoto(path) } },
                    )
                }
            }
        }
    }
}
