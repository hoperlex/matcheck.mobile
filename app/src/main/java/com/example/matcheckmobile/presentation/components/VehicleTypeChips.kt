package com.example.matcheckmobile.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.matcheckmobile.domain.model.VEHICLE_TYPES
import com.example.matcheckmobile.domain.model.VehicleType

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
                VehicleChip(
                    type = type,
                    selected = type.code == selectedCode,
                    onClick = { onSelected(type) },
                    iconHeight = iconHeight,
                    showSubtitle = showSubtitle,
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

private fun formatNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
