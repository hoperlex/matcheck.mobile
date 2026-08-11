package com.example.matcheckmobile.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Заголовок-карточка группы в раскрывающемся списке: имя группы, счётчик
 * строк и chevron. По какому признаку сгруппировано — решает вызывающий:
 * поставщик на экранах приёмки/отгрузки, дата в архиве. UI одинаковый,
 * чтобы пользователь везде видел знакомую структуру.
 */
@Composable
fun GroupHeaderCard(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron-rotation",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                modifier = Modifier
                    .size(28.dp)
                    .rotate(chevronRotation),
            )
        }
    }
}

/**
 * Карточка одной строки в раскрытой группе: крупный заголовок (обычно «УПД №…»)
 * и подзаголовок (обычно «Поставщик: …»). По тапу — переход к форме этапа.
 */
@Composable
fun UpdSummaryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    started: Boolean = false,
    timer: UpdTimerBadge? = null,
) {
    // Резерв справа под бейдж: для таймера нужно больше (текст «1д 1ч 23мин»),
    // для «Начато» — фикс. Если оба, приоритет у таймера.
    val rightPadding = when {
        timer != null -> 132.dp
        started -> 96.dp
        else -> 16.dp
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = rightPadding, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            when {
                timer != null -> TimerBadge(
                    timer = timer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                )
                started -> StartedBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                )
            }
        }
    }
}

/**
 * Таймер «время в работе» для карточки УПД на 2 Этапе. До 2 часов — жёлтый,
 * после — красный. Текст формируется снаружи (например, `1ч 23мин`).
 */
data class UpdTimerBadge(
    val text: String,
    val overdue: Boolean,
)

@Composable
private fun TimerBadge(timer: UpdTimerBadge, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "timer-badge")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "timer-badge-alpha",
    )
    val container = if (timer.overdue) TimerOverdueContainer else StartedBadgeContainer
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = Color.White,
        modifier = modifier.alpha(alpha),
    ) {
        Text(
            text = timer.text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private val TimerOverdueContainer = Color(0xFFD32F2F) // red 700 — совпадает с OverdueDot на кнопке 1 Этапа

/**
 * «Начато» — зелёный пилюлеобразный лейбл с плавным пульсирующим alpha.
 * Период ~1.8с (туда-обратно), easing linear, чтобы не «дёргалось». Цвет
 * фиксированный (не из темы), чтобы на любой Surface оставался узнаваемо
 * зелёным — это статусный маркер, а не декор.
 */
@Composable
private fun StartedBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "started-badge")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "started-badge-alpha",
    )
    Surface(
        shape = MaterialTheme.shapes.small,
        color = StartedBadgeContainer,
        contentColor = StartedBadgeContent,
        modifier = modifier.alpha(alpha),
    ) {
        Text(
            text = "Начато",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private val StartedBadgeContainer = Color(0xFFF9A825) // yellow 800 — янтарный, достаточно тёмный для белого текста
private val StartedBadgeContent = Color.White
