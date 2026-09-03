package com.example.matcheckmobile.ui.icons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Сверка десяти иконок, перенесённых из `material-icons-extended` в [LocalIcons].
 *
 * Только для debug: в релизе этого кода нет. Смысл — посмотреть все контуры разом,
 * а не обходить 13 экранов, на которых они разбросаны. Если иконка выглядит битой,
 * значит разъехались координаты в [LocalIcons], а не вёрстка экрана.
 */
@Preview(showBackground = true, widthDp = 240)
@Composable
private fun LocalIconsPreview() {
    val icons = listOf(
        "BrokenImage" to LocalIcons.BrokenImage,
        "CloudOff" to LocalIcons.CloudOff,
        "DeleteOutline" to LocalIcons.DeleteOutline,
        "Logout" to LocalIcons.Logout,
        "PhotoCamera" to LocalIcons.PhotoCamera,
        "Sync" to LocalIcons.Sync,
        "SystemUpdate" to LocalIcons.SystemUpdate,
        "Verified" to LocalIcons.Verified,
        "Visibility" to LocalIcons.Visibility,
        "VisibilityOff" to LocalIcons.VisibilityOff,
    )
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        icons.forEach { (name, icon) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = name, modifier = Modifier.size(24.dp))
                Text(name)
            }
        }
    }
}
