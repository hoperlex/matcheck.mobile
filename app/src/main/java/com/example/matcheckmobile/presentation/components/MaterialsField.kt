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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions

/** Одна позиция материала: название + количество + единица измерения. */
data class MaterialDraft(
    val name: String,
    val qty: String,
    val unit: String = "",
)

/**
 * Убирает trailing-нули и точку у decimal-строки: "2.0000" → "2",
 * "2.5000" → "2.5". Сервер хранит Decimal как строку с фиксированной
 * точностью, инспектору удобнее короткий вид. Нечисловые строки возвращаются
 * как есть.
 */
private fun String.compactDecimal(): String {
    if (isEmpty() || !contains('.')) return this
    val cleaned = trimEnd('0').trimEnd('.')
    return cleaned.ifEmpty { "0" }
}

/**
 * Поле «Материалы». В обычном режиме — псевдо-инпут с однострочной сводкой
 * и редактором по тапу. В режиме [readOnly] — короткая кнопка-чип «Материалы (N)»,
 * по тапу открывает диалог-просмотр без редактирования и без добавления.
 */
@Composable
fun MaterialsField(
    value: List<MaterialDraft>,
    onValueChange: (List<MaterialDraft>) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Материалы",
    readOnly: Boolean = false,
    buttonTextStyle: TextStyle? = null,
    buttonMinHeight: Dp = 56.dp,
) {
    var dialogVisible by remember { mutableStateOf(false) }

    if (readOnly) {
        val count = value.count { it.name.isNotBlank() }
        OutlinedButton(
            onClick = { dialogVisible = true },
            shape = RoundedCornerShape(12.dp),
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = buttonMinHeight),
        ) {
            Text(
                text = "$label  ($count)",
                style = buttonTextStyle ?: MaterialTheme.typography.titleMedium,
            )
        }
    } else {
        val display = value
            .filter { it.name.isNotBlank() }
            .joinToString(" · ") { draft ->
                val qty = draft.qty.compactDecimal()
                if (qty.isBlank()) draft.name else "${draft.name} · $qty"
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
    }

    if (dialogVisible) {
        MaterialsEditorDialog(
            initial = value,
            readOnly = readOnly,
            onDismiss = { result ->
                if (!readOnly) onValueChange(result)
                dialogVisible = false
            },
        )
    }
}

@Composable
private fun MaterialsEditorDialog(
    initial: List<MaterialDraft>,
    onDismiss: (List<MaterialDraft>) -> Unit,
    readOnly: Boolean = false,
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
                        modifier = Modifier.weight(0.65f),
                    )
                    Text(
                        text = "Кол-во",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(0.2f),
                    )
                    Text(
                        text = "Ед.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(0.15f),
                    )
                }

                if (items.isEmpty()) {
                    Text(
                        text = "Список пуст",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(items, key = { index, _ -> index }) { index, draft ->
                            if (!readOnly && editingIndex == index) {
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
                                val rowClick: (() -> Unit)? = if (readOnly) null else {
                                    {
                                        commitEdit()
                                        editingName = draft.name
                                        editingQty = draft.qty.compactDecimal()
                                        editingIndex = index
                                    }
                                }
                                MaterialRow(
                                    number = index + 1,
                                    draft = draft,
                                    onClick = rowClick,
                                )
                            }
                        }
                    }
                }

                if (readOnly) {
                    OutlinedButton(
                        onClick = { onDismiss(result()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Закрыть")
                    }
                } else {
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
}

@Composable
private fun MaterialRow(
    number: Int,
    draft: MaterialDraft,
    onClick: (() -> Unit)?,
) {
    val baseModifier = Modifier.fillMaxWidth()
    val cardModifier = if (onClick != null) baseModifier.clickable(onClick = onClick) else baseModifier
    Card(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = cardModifier,
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
                modifier = Modifier.weight(0.65f),
            )
            Text(
                text = draft.qty.compactDecimal().ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.2f),
            )
            Text(
                text = draft.unit.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.15f),
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

/**
 * Inline-таблица материалов — та же шапка и карточки, что в [MaterialsField]
 * read-only диалоге, но прямо на экране, без обёртки в Card/Dialog и без
 * редактирования. Используется на 2 Этапе для просмотра уже сохранённого
 * списка.
 */
@Composable
fun MaterialsInlineList(
    value: List<MaterialDraft>,
    modifier: Modifier = Modifier,
    headerStyle: TextStyle? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Название",
                style = headerStyle ?: MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.65f),
            )
            Text(
                text = "Кол-во",
                style = headerStyle ?: MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.2f),
            )
            Text(
                text = "Ед.",
                style = headerStyle ?: MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.15f),
            )
        }
        if (value.isEmpty()) {
            Text(
                text = "Список пуст",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            value.forEachIndexed { index, draft ->
                MaterialRow(
                    number = index + 1,
                    draft = draft,
                    onClick = null,
                )
            }
        }
    }
}
