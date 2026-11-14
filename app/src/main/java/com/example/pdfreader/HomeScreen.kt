package com.example.pdfreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Недавний файл вместе с сохранённой страницей (0-based). */
data class RecentEntry(val file: RecentFile, val page: Int)

@Composable
fun HomeScreen(
    recent: List<RecentEntry>,
    snackbar: SnackbarHostState,
    onOpen: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onRemoveRecent: (RecentFile) -> Unit,
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (recent.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(onOpen)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Header(onOpen)
                        Spacer(Modifier.height(32.dp))
                    }
                }
                item {
                    Text(
                        "Недавние",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .widthIn(max = 600.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(recent, key = { it.file.uri.toString() }) { entry ->
                    RecentRow(
                        entry = entry,
                        onClick = { onOpenRecent(entry.file) },
                        onRemove = { onRemoveRecent(entry.file) },
                        modifier = Modifier.animateItem().widthIn(max = 600.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(onOpen: () -> Unit) {
    Icon(
        Icons.Filled.PictureAsPdf,
        contentDescription = null,
        modifier = Modifier.size(96.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(16.dp))
    Text("PDF Reader", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Тап по странице — полноэкранный режим",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onOpen, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(56.dp)) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null)
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text("Открыть PDF")
    }
}

@Composable
private fun RecentRow(entry: RecentEntry, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val pageCount = entry.file.pageCount
    val progress = if (pageCount > 0) "Стр. ${entry.page + 1} из $pageCount" else "Стр. ${entry.page + 1}"
    ListItem(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        leadingContent = {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        headlineContent = { Text(entry.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(progress) },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Убрать из списка")
            }
        },
    )
}
