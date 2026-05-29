package com.example.matcheckmobile.presentation.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Одна позиция материала: название + количество + единица измерения.
 *
 * Опциональные поля `id`, `price`, `vatRate`, `vatSum` — снимок серверной
 * позиции delivery_items/shipment_items. На 1 Этапе они null (позиции
 * создаются с нуля). На 2 Этапе при загрузке заполняются из БД и должны
 * быть возвращены обратно в upsert payload — иначе сервер обнулит
 * Цена/НДС в карточке приёмки.
 */
data class MaterialDraft(
    val name: String,
    val qty: String,
    val unit: String = "",
    val id: String? = null,
    val price: String? = null,
    val vatRate: String? = null,
    val vatSum: String? = null,
)

/**
 * Пересчитывает сумму НДС позиции по формуле веб-портала (см. computeVatSum
 * в apps/web/.../KppPage.tsx): `vatSum = qty * price * vatRate / 100`.
 * Цена price хранится как «за единицу без НДС», поэтому при изменении
 * количества инспектором сумма НДС обязана пересчитаться.
 *
 * Возвращает строку с 2 знаками после точки (как сервер хранит DECIMAL) или
 * null, если любого из аргументов нет / он невалиден. В этом случае
 * вызывающий код использует сохранённое значение initial.vatSum.
 */
private fun computeVatSum(qty: String?, price: String?, vatRate: String?): String? {
    val qtyN = qty?.replace(',', '.')?.toDoubleOrNull() ?: return null
    val priceN = price?.replace(',', '.')?.toDoubleOrNull() ?: return null
    val rateN = vatRate?.replace(',', '.')?.toDoubleOrNull() ?: return null
    val result = qtyN * priceN * rateN / 100.0
    return "%.2f".format(result).replace(',', '.')
}

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
    // Телефон менеджера-автора УПД. Если задан и не пустой, в шапке диалога
    // справа от заголовка «Материалы» рисуется иконка звонка; тап открывает
    // системный диалер с подставленным номером (ACTION_DIAL — не требует
    // CALL_PHONE permission, работает оффлайн). Используется только на
    // 1 Этапе приёмки, в других местах вызова MaterialsField параметр
    // не передаётся (по умолчанию null) и иконка не появляется.
    managerPhone: String? = null,
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
            managerPhone = managerPhone,
            onDismiss = { result ->
                if (!readOnly) onValueChange(result)
                dialogVisible = false
            },
        )
    }
}

/**
 * Шапка модалки «Материалы»: заголовок слева, опциональная иконка звонка
 * справа. Иконка появляется только если задан непустой [managerPhone].
 * Layout — Row с заголовком на weight(1f) + maxLines=1/ellipsis, чтобы на
 * узких телефонах (например, 360dp) заголовок ужимался, а не выталкивал
 * иконку за край.
 */
@Composable
private fun MaterialsDialogHeader(managerPhone: String?) {
    val context = LocalContext.current
    val phone = managerPhone?.trim()?.takeIf { it.isNotEmpty() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Материалы",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (phone != null) {
            // Текст «Менеджер» + иконка звонка как единая кликабельная зона —
            // и понятнее сотруднику (без подписи иконка читалась бы как
            // абстрактная кнопка), и больше площадь для тапа на узких телефонах.
            TextButton(
                onClick = {
                    val telUri = Uri.parse("tel:" + phone.normalizePhoneForDial())
                    val intent = Intent(Intent.ACTION_DIAL, telUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    // ACTION_DIAL не требует CALL_PHONE permission, работает оффлайн
                    // (открывает системный диалер с подставленным номером).
                    // runCatching — на случай устройств без dialer-приложения
                    // (планшеты без SIM), чтобы не словить ActivityNotFoundException.
                    runCatching { context.startActivity(intent) }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "Менеджер",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Позвонить менеджеру",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Нормализация номера для tel:URI. Системный диалер принимает символы из
 * RFC 3966, но самый совместимый вид — только цифры и ведущий «+». Убираем
 * пробелы, скобки, дефисы. «8XXXXXXXXXX» оставляем как есть — диалер сам
 * наберёт; принудительно менять на +7 не стоит, потому что в коде стран СНГ
 * могут быть локальные «8» с другим значением.
 */
private fun String.normalizePhoneForDial(): String =
    replace(Regex("[^+0-9]"), "")

@Composable
private fun MaterialsEditorDialog(
    initial: List<MaterialDraft>,
    onDismiss: (List<MaterialDraft>) -> Unit,
    readOnly: Boolean = false,
    managerPhone: String? = null,
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
            if (name.isEmpty() && qty.isEmpty()) {
                items.removeAt(idx)
            } else {
                // Сохраняем id/unit/price/vatRate/vatSum из существующей строки —
                // не теряем server-side финансы при правке. См. EditMaterialDialog
                // ниже, тот же принцип.
                val existing = items[idx]
                items[idx] = existing.copy(name = name, qty = qty)
            }
        }
        editingIndex = null
        editingName = ""
        editingQty = ""
    }

    fun result(): List<MaterialDraft> = items
        .map { it.copy(name = it.name.trim(), qty = it.qty.trim()) }
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
                MaterialsDialogHeader(managerPhone = managerPhone)
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
    strikethroughName: Boolean = false,
    // Если задано — qty был отредактирован. Старое значение рендерится
    // перечёркнутым, новое — в скобках рядом. Например: «2 (5)», где «2» —
    // зачёркнутый original, «(5)» — текущее значение draft.qty.
    originalQty: String? = null,
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
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (strikethroughName)
                        androidx.compose.ui.text.style.TextDecoration.LineThrough
                    else
                        null,
                ),
                modifier = Modifier.weight(0.65f),
            )
            QtyCell(
                currentQty = draft.qty,
                originalQty = originalQty,
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
private fun QtyCell(
    currentQty: String,
    originalQty: String?,
    modifier: Modifier = Modifier,
) {
    val currentText = currentQty.compactDecimal()
    val originalText = originalQty?.compactDecimal()
    val isEdited = originalText != null && originalText != currentText
    if (isEdited) {
        // «N (M)» — где N перечёркнутое старое, M — текущее в скобках.
        val annotated = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                    color = androidx.compose.ui.graphics.Color.Unspecified,
                ),
            ) {
                append(originalText!!.ifBlank { "—" })
            }
            append(" (")
            append(currentText.ifBlank { "—" })
            append(")")
        }
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
    } else {
        Text(
            text = currentText.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
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

/**
 * Inline-таблица материалов с редактированием. По тапу на строку открывается
 * диалог правки (название + количество), свайп вправо удаляет строку.
 * Изменённые строки (индекс ∈ [editedIndexes]) подсвечиваются перечёркнутым
 * названием. Используется на 2 Этапе.
 */
/**
 * Шапка inline-таблицы материалов («Название / Кол-во / Ед.»). Вынесена,
 * чтобы её можно было закрепить sticky-зоной над прокручиваемым списком —
 * иначе при скролле списка наверх заголовки уезжают за рамки видимости.
 *
 * Опционально показывает компактную кнопку «+ Добавить» между «Название» и
 * «Кол-во» — нужна на 2 Этапе, чтобы донабрать материал, отсутствующий в
 * серверной приёмке. Если [onAddClick] не передан — кнопка не отрисовывается.
 */
@Composable
fun MaterialsTableHeader(
    modifier: Modifier = Modifier,
    headerStyle: TextStyle? = null,
    onAddClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Название",
            style = headerStyle ?: MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Когда кнопка встроена в шапку, делаем слоты «Название» и «Кол-во»
            // равными по ширине, оба с textAlign=Center — тогда кнопка между
            // ними визуально стоит ровно по центру, а не «прижата» к Кол-во.
            textAlign = if (onAddClick != null) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(if (onAddClick != null) 0.22f else 0.65f),
            maxLines = 1,
        )
        if (onAddClick != null) {
            // На планшете надпись помещается одной строкой, на узких телефонах
            // — «материал» переносится на 2-ю строку (softWrap=true, maxLines=2).
            // Высоту кнопки расширили, чтобы 2 строки текста не упирались
            // в рамку.
            OutlinedButton(
                onClick = onAddClick,
                modifier = Modifier
                    .weight(0.45f)
                    .heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Добавить материал",
                    style = headerStyle ?: MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    softWrap = true,
                    lineHeight = 16.sp,
                )
            }
        }
        Text(
            text = "Кол-во",
            style = headerStyle ?: MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(if (onAddClick != null) 0.22f else 0.2f),
            maxLines = 1,
        )
        Text(
            text = "Ед.",
            style = headerStyle ?: MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(if (onAddClick != null) 0.11f else 0.15f),
            maxLines = 1,
        )
    }
}

/**
 * Модалка ручного ввода материала. На 2 Этапе её открывает кнопка «Добавить
 * материал» (showUnitField=true — нужно выбрать единицу), на правке строки —
 * showUnitField=false (юзер меняет только название/количество, ед.измерения
 * приходит с сервера и менять её не должны).
 */
@Composable
fun MaterialEditDialog(
    initial: MaterialDraft,
    onDismiss: () -> Unit,
    onSave: (MaterialDraft) -> Unit,
    title: String = "Редактировать материал",
    showUnitField: Boolean = true,
) {
    EditMaterialDialog(
        initial = initial,
        onDismiss = onDismiss,
        onSave = onSave,
        title = title,
        showUnitField = showUnitField,
    )
}

@Composable
fun EditableMaterialsInlineList(
    value: List<MaterialDraft>,
    editedIndexes: Set<Int>,
    onEdit: (index: Int, draft: MaterialDraft) -> Unit,
    onDelete: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    headerStyle: TextStyle? = null,
    showHeader: Boolean = true,
    // Оригинальные значения для каждой серверной строки. Используется для
    // подсветки изменений: имя перечёркивается только если оно реально
    // изменилось, qty рендерится как «old (new)» при правке. Для строк,
    // добавленных вручную (нет в originalMaterials), оригинала нет.
    originalMaterials: List<MaterialDraft>? = null,
    // На 2 Этапе при правке существующей строки скрываем поле «Ед.» —
    // редактируется только Название + Количество. Для add-сценария поле
    // отображается через отдельный вызов MaterialEditDialog.
    editingShowUnitField: Boolean = true,
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showHeader) {
            MaterialsTableHeader(headerStyle = headerStyle)
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
                val original = originalMaterials?.getOrNull(index)
                val nameEdited = original != null && original.name != draft.name
                val qtyOriginalForDisplay = original
                    ?.qty
                    ?.takeIf { it != draft.qty }
                // Ключ swipe-state — содержимое строки. После delete индексы
                // сдвигаются, и привязка по index не пережила бы recomposition.
                key(draft.name, draft.qty, draft.unit, index) {
                    SwipeableMaterialRow(
                        number = index + 1,
                        draft = draft,
                        strikethroughName = nameEdited,
                        originalQty = qtyOriginalForDisplay,
                        onTap = { editingIndex = index },
                        onSwipeDelete = { onDelete(index) },
                    )
                }
            }
        }
    }

    editingIndex?.let { idx ->
        if (idx in value.indices) {
            EditMaterialDialog(
                initial = value[idx],
                onDismiss = { editingIndex = null },
                onSave = { newDraft ->
                    onEdit(idx, newDraft)
                    editingIndex = null
                },
                showUnitField = editingShowUnitField,
            )
        } else {
            editingIndex = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMaterialRow(
    number: Int,
    draft: MaterialDraft,
    strikethroughName: Boolean,
    originalQty: String?,
    onTap: () -> Unit,
    onSwipeDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onSwipeDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            // Красный фон с иконкой корзины, выровненной слева — куда идёт свайп.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        },
    ) {
        MaterialRow(
            number = number,
            draft = draft,
            onClick = onTap,
            strikethroughName = strikethroughName,
            originalQty = originalQty,
        )
    }
}

@Composable
private fun EditMaterialDialog(
    initial: MaterialDraft,
    onDismiss: () -> Unit,
    onSave: (MaterialDraft) -> Unit,
    title: String = "Редактировать материал",
    showUnitField: Boolean = true,
) {
    var name by remember { mutableStateOf(initial.name) }
    var qty by remember { mutableStateOf(initial.qty.compactDecimal()) }
    // Ед.измерения редактируется только в add-сценарии. В edit-режиме оно
    // приходит с сервера и не должно меняться пользователем — поле скрыто,
    // значение пробрасывается из initial.
    var unit by remember { mutableStateOf(initial.unit) }
    val nameFocus = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = false,
                    minLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus),
                )
                if (showUnitField) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = qty,
                            onValueChange = { qty = it },
                            label = { Text("Количество") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("Ед.") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = qty,
                        onValueChange = { qty = it },
                        label = { Text("Количество") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Отмена")
                    }
                    Button(
                        onClick = {
                            // Критично: при правке существующей строки на 2 Этапе
                            // пробрасываем id и финансы (price/vatRate) из initial,
                            // а vatSum пересчитываем под новый qty — иначе при
                            // upsert на сервер уйдёт устаревший vatSum от старого
                            // количества (или null, если поля не пробросить), и
                            // веб-портал будет показывать неправильную сумму НДС.
                            // Для add-сценария (новая строка) initial — пустой,
                            // computeVatSum вернёт null, всё корректно null'ится.
                            val newQty = qty.trim()
                            val recomputedVatSum = computeVatSum(
                                qty = newQty,
                                price = initial.price,
                                vatRate = initial.vatRate,
                            )
                            onSave(
                                MaterialDraft(
                                    name = name.trim(),
                                    qty = newQty,
                                    unit = unit.trim(),
                                    id = initial.id,
                                    price = initial.price,
                                    vatRate = initial.vatRate,
                                    vatSum = recomputedVatSum ?: initial.vatSum,
                                )
                            )
                        },
                        enabled = name.isNotBlank() || qty.isNotBlank(),
                    ) {
                        Text("Сохранить")
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        nameFocus.requestFocus()
    }
}
