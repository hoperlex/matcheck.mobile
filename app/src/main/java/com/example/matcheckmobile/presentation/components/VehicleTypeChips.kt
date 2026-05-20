package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.domain.model.VEHICLE_TYPES
import com.example.matcheckmobile.domain.model.VehicleType

/**
 * Суммарная загрузка по материалам выбранной УПД: объём в м³ и масса в тоннах.
 * null означает, что у позиций УПД нет соответствующих данных — gauge
 * нарисуется как «нет данных», а не как 0.
 */
data class VehicleLoadInfo(
    val totalVolumeM3: Double?,
    val totalMassT: Double?,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleTypeChips(
    selectedCode: String?,
    onSelected: (VehicleType) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = "Тип транспорта",
    maxItemsInRow: Int = VEHICLE_TYPES.size,
    iconHeight: Dp = 36.dp,
    showSubtitle: Boolean = true,
    loadInfo: VehicleLoadInfo? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = maxItemsInRow,
        ) {
            VEHICLE_TYPES.forEach { type ->
                val isSelected = type.code == selectedCode
                VehicleChip(
                    type = type,
                    selected = isSelected,
                    onClick = { onSelected(type) },
                    iconHeight = iconHeight,
                    showSubtitle = showSubtitle,
                    loadInfo = if (isSelected) loadInfo else null,
                    modifier = Modifier.weight(1f, fill = true),
                )
            }
        }
    }
}

@Composable
private fun VehicleChip(
    type: VehicleType,
    selected: Boolean,
    onClick: () -> Unit,
    iconHeight: Dp,
    showSubtitle: Boolean,
    loadInfo: VehicleLoadInfo?,
    modifier: Modifier = Modifier,
) {
    val container = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (selected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (selected)
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    else
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        if (loadInfo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(0.45f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(iconHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = type.iconRes),
                            contentDescription = type.name,
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(onContainer.copy(alpha = 0.85f)),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = type.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                LoadGauge(
                    type = type,
                    info = loadInfo,
                    modifier = Modifier.weight(0.55f),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(iconHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = type.iconRes),
                        contentDescription = type.name,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(onContainer.copy(alpha = 0.85f)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = type.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                if (showSubtitle) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${formatNum(type.volumeM3)} м³",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "${formatNum(type.payloadTons)} т",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadGauge(
    type: VehicleType,
    info: VehicleLoadInfo,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LoadColumn(
            title = "Объём",
            used = info.totalVolumeM3,
            capacity = type.volumeM3,
            unit = "м³",
            modifier = Modifier.weight(1f),
        )
        LoadColumn(
            title = "Масса",
            used = info.totalMassT,
            capacity = type.payloadTons,
            unit = "т",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LoadColumn(
    title: String,
    used: Double?,
    capacity: Double,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val muted = Color(0xFFBFBFBF)
    if (used == null) {
        // Нет данных по этой характеристике в позициях УПД — рисуем пустой
        // серый бар и подпись «нет данных», чтобы не вводить в заблуждение
        // нулём.
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "—",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = muted,
            )
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = "нет данных",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
            )
        }
        return
    }

    val ratio = if (capacity > 0) (used / capacity).toFloat() else 0f
    val percent = (ratio * 100).toInt()
    // Пороги синхронизированы с web/VehicleFillGauge.tsx — pctColor().
    val barColor = when {
        percent > 100 -> Color(0xFFCF1322)  // красный — перегруз
        percent >= 75 -> Color(0xFFFA8C16)  // оранжевый — почти полный
        percent >= 50 -> Color(0xFF52C41A)  // зелёный — нормально
        percent >= 25 -> Color(0xFF1677FF)  // синий — половинная загрузка
        else -> Color(0xFFBFBFBF)            // серый — пустовато
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = barColor,
        )
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(ratio.coerceIn(0f, 1f))
                    .background(barColor),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            text = "${formatNum(used)}/${formatNum(capacity)} $unit",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

private fun formatNum(v: Double): String {
    if (v % 1.0 == 0.0) return v.toLong().toString()
    return "%.2f".format(v).trimEnd('0').trimEnd('.', ',')
}
