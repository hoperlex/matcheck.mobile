package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
 * Однострочный «псевдо-инпут» для материалов: показывает текущее значение
 * (как ModalTextField), но при тапе открывает специализированный
 * редактор-список — каждый материал отдельной кликабельной строкой
 * с бордером, нумерацией и переносом длинного текста.
 *
 * Внутреннее представление — multi-line строка ("материал1\n материал2\n...").
 */
@Composable
fun MaterialsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Материалы",
) {
    var dialogVisible by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value.replace("\n", " · "),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("Нажмите для ввода") },
            modifier = Modifier.fillMaxWidth(),
        )
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
        MaterialsEditorDialog(
            initialValue = value,
            onDismiss = { result ->
                onValueChange(result)
                dialogVisible = false
            },
        )
    }
}

@Composable
private fun MaterialsEditorDialog(
    initialValue: String,
    onDismiss: (String) -> Unit,
) {
    val items = remember {
        mutableStateListOf<String>().apply {
            addAll(initialValue.split("\n").map { it.trim() }.filter { it.isNotEmpty() })
        }
    }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }

    fun commitEdit() {
        editingIndex?.let { idx ->
            val trimmed = editingText.trim()
            if (trimmed.isEmpty()) items.removeAt(idx) else items[idx] = trimmed
        }
        editingIndex = null
        editingText = ""
    }

    fun resultText(): String = items.joinToString("\n") { it.trim() }.trim()

    Dialog(
        onDismissRequest = {
            commitEdit()
            onDismiss(resultText())
        },
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Материалы",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Название",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(items, key = { index, _ -> index }) { index, text ->
                        if (editingIndex == index) {
                            EditableMaterialRow(
                                number = index + 1,
                                value = editingText,
                                onValueChange = { editingText = it },
                                onCommit = { commitEdit() },
                                onDelete = {
                                    items.removeAt(index)
                                    editingIndex = null
                                    editingText = ""
                                },
                            )
                        } else {
                            MaterialRow(
                                number = index + 1,
                                text = text,
                                onClick = {
                                    commitEdit()
                                    editingText = text
                                    editingIndex = index
                                },
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        commitEdit()
                        items.add("")
                        editingText = ""
                        editingIndex = items.lastIndex
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("+ Добавить материал")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = {
                            items.clear()
                            editingIndex = null
                            editingText = ""
                        },
                        enabled = items.isNotEmpty(),
                    ) {
                        Text("Стереть")
                    }
                    Button(
                        onClick = {
                            commitEdit()
                            onDismiss(resultText())
                        },
                    ) {
                        Text("Добавить")
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialRow(
    number: Int,
    text: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "$number.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(28.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(0.75f),
            )
            // 25% справа — пустое пространство, чтобы длинный текст
            // переносился, не доходя до правого края.
            Spacer(modifier = Modifier.weight(0.25f))
        }
    }
}

@Composable
private fun EditableMaterialRow(
    number: Int,
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onDelete: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Card(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$number.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(28.dp),
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = false,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            IconButton(onClick = onCommit) {
                Icon(Icons.Default.Check, contentDescription = "Готово")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
