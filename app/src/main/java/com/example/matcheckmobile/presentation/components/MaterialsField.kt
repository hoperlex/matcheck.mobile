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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions

/** Одна позиция материала: название + количество. */
data class MaterialDraft(
    val name: String,
    val qty: String,
)

/**
 * Однострочный «псевдо-инпут» для материалов: показывает количество позиций,
 * по тапу открывает редактор-список. Каждая строка — название (75% ширины)
 * и количество (25% справа), при тапе превращается в inline-редактор.
 */
@Composable
fun MaterialsField(
    value: List<MaterialDraft>,
    onValueChange: (List<MaterialDraft>) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Материалы",
) {
    var dialogVisible by remember { mutableStateOf(false) }
    val display = value
        .filter { it.name.isNotBlank() }
        .joinToString(" · ") { draft ->
            if (draft.qty.isBlank()) draft.name else "${draft.name} · ${draft.qty}"
        }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = display,
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
            initial = value,
            onDismiss = { result ->
                onValueChange(result)
                dialogVisible = false
            },
        )
    }
}

@Composable
private fun MaterialsEditorDialog(
    initial: List<MaterialDraft>,
    onDismiss: (List<MaterialDraft>) -> Unit,
) {
    val items = remember {
        mutableStateListOf<MaterialDraft>().apply { addAll(initial) }
    }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingName by remember { mutableStateOf("") }
    var editingQty by remember { mutableStateOf("") }

    fun commitEdit() {
        editingIndex?.let { idx ->
            val name = editingName.trim()
            val qty = editingQty.trim()
            if (name.isEmpty() && qty.isEmpty()) items.removeAt(idx)
            else items[idx] = MaterialDraft(name = name, qty = qty)
        }
        editingIndex = null
        editingName = ""
        editingQty = ""
    }

    fun result(): List<MaterialDraft> = items
        .map { MaterialDraft(name = it.name.trim(), qty = it.qty.trim()) }
        .filter { it.name.isNotEmpty() || it.qty.isNotEmpty() }

    Dialog(
        onDismissRequest = {
            commitEdit()
            onDismiss(result())
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
                // Шапка двух столбцов.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Название",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.75f),
                    )
                    Text(
                        text = "Количество",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.25f),
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(items, key = { index, _ -> index }) { index, draft ->
                        if (editingIndex == index) {
                            EditableMaterialRow(
                                number = index + 1,
                                name = editingName,
                                qty = editingQty,
                                onNameChange = { editingName = it },
                                onQtyChange = { editingQty = it },
                                onCommit = { commitEdit() },
                                onDelete = {
                                    items.removeAt(index)
                                    editingIndex = null
                                    editingName = ""
                                    editingQty = ""
                                },
                            )
                        } else {
                            MaterialRow(
                                number = index + 1,
                                draft = draft,
                                onClick = {
                                    commitEdit()
                                    editingName = draft.name
                                    editingQty = draft.qty
                                    editingIndex = index
                                },
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        commitEdit()
                        items.add(MaterialDraft("", ""))
                        editingName = ""
                        editingQty = ""
                        editingIndex = items.lastIndex
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("+ Добавить материал")
                }
            }
        }
    }
}

@Composable
private fun MaterialRow(
    number: Int,
    draft: MaterialDraft,
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "$number.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(28.dp),
            )
            Text(
                text = draft.name.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(0.75f),
            )
            Text(
                text = draft.qty.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(0.25f),
            )
        }
    }
}

@Composable
private fun EditableMaterialRow(
    number: Int,
    name: String,
    qty: String,
    onNameChange: (String) -> Unit,
    onQtyChange: (String) -> Unit,
    onCommit: () -> Unit,
    onDelete: () -> Unit,
) {
    val nameFocus = remember { FocusRequester() }
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "$number.",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(28.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = false,
                modifier = Modifier
                    .weight(0.75f)
                    .focusRequester(nameFocus),
            )
            OutlinedTextField(
                value = qty,
                onValueChange = onQtyChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(0.25f),
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
        nameFocus.requestFocus()
    }
}
