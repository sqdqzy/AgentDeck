package com.agentdeck.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentdeck.data.model.SiteCard

/**
 * Editor for a site card.
 *
 * Long-pressing a card opens this dialog, where the name, URL, icon and the
 * bound adapter can be changed.
 */
@Composable
fun SiteEditDialog(
    card: SiteCard,
    onDismiss: () -> Unit,
    onSave: (SiteCard) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(card.id) { mutableStateOf(card.name) }
    var url by remember(card.id) { mutableStateOf(card.url) }
    var iconKey by remember(card.id) { mutableStateOf(card.iconKey) }
    var adapterId by remember(card.id) { mutableStateOf(card.adapterId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑卡片") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = iconKey,
                    onValueChange = { iconKey = it },
                    label = { Text("图标标识") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = adapterId,
                    onValueChange = { adapterId = it },
                    label = { Text("站点适配器 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        card.copy(
                            name = name.trim().ifEmpty { card.name },
                            url = url.trim().ifEmpty { card.url },
                            iconKey = iconKey.trim().ifEmpty { card.iconKey },
                            adapterId = adapterId.trim().ifEmpty { card.adapterId },
                        ),
                    )
                    onDismiss()
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = {
                        onDelete()
                        onDismiss()
                    }) {
                        Text("删除")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
