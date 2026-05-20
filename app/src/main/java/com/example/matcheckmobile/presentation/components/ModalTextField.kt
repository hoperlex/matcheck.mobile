package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Однострочный «псевдо-инпут». По тапу открывается модальное окно с
 * полноценным многострочным полем ввода — это удобнее системной клавиатуры,
 * которая на планшетах закрывает половину экрана.
 *
 * Visual: внешне как обычный OutlinedTextField, но клик ловит прозрачная
 * плёнка сверху — поэтому фокус не уходит в TextField и клавиатура не
 * поднимается. Используется для длинных текстовых полей (Материалы,
 * Комментарий и т.п.).
 */
@Composable
fun ModalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Нажмите для ввода",
    isError: Boolean = false,
    compact: Boolean = false,
    forceUppercase: Boolean = false,
) {
    var dialogVisible by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            isError = isError,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Прозрачная плёнка, чтобы тап не поднимал системную клавиатуру.
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dialogVisible = true },
                ),
        )
    }

    if (dialogVisible) {
        ModalTextFieldDialog(
            initialValue = value,
            label = label,
            compact = compact,
            forceUppercase = forceUppercase,
            onDismiss = { result ->
                onValueChange(result)
                dialogVisible = false
            },
        )
    }
}

@Composable
private fun ModalTextFieldDialog(
    initialValue: String,
    label: String,
    onDismiss: (resultText: String) -> Unit,
    compact: Boolean = false,
    forceUppercase: Boolean = false,
) {
    var draft by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = { onDismiss(draft) },
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = !compact,
        ),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = if (compact) {
                Modifier.widthIn(max = 360.dp)
            } else {
                Modifier.fillMaxWidth()
            },
        ) {
            Column(
                modifier = Modifier.padding(if (compact) 16.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = label,
                    style = if (compact) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { input ->
                        draft = if (forceUppercase) input.uppercase() else input
                    },
                    modifier = if (compact) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                            .focusRequester(focusRequester)
                    },
                    singleLine = compact,
                    minLines = if (compact) 1 else 5,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = { draft = "" },
                        enabled = draft.isNotEmpty(),
                    ) {
                        Text("Стереть")
                    }
                    Button(
                        onClick = { onDismiss(draft) },
                        enabled = draft.isNotBlank(),
                    ) {
                        Text("Добавить")
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
