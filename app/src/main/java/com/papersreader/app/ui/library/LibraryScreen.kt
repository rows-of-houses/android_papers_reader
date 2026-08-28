package com.papersreader.app.ui.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.papersreader.app.data.db.PaperEntity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    pendingImportUri: String?,
    onPendingImportConsumed: () -> Unit,
    onOpenPaper: (Long) -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val papers by viewModel.papers.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val onOpenInFiles: (PaperEntity) -> Unit = { paper ->
        val file = viewModel.paperFile(paper)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar("No app found to open the file") }
        }
    }

    val pickPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFromUri(it.toString()) }
    }

    LaunchedEffect(pendingImportUri) {
        pendingImportUri?.let {
            viewModel.importFromUri(it)
            onPendingImportConsumed()
        }
    }

    LaunchedEffect(importState) {
        val state = importState
        if (state is ImportState.Error) {
            scope.launch {
                snackbarHostState.showSnackbar(state.message)
                viewModel.dismissError()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My library") },
                actions = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Logs")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Add PDF") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (importState is ImportState.Importing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Importing…")
                }
            }

            if (papers.isEmpty() && importState !is ImportState.Importing) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "No papers yet. Tap \"Add PDF\" to import one, or open a PDF from another app.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn {
                    items(papers, key = { it.id }) { paper ->
                        PaperRow(
                            paper = paper,
                            onClick = { onOpenPaper(paper.id) },
                            onCopyTitle = {
                                clipboardManager.setText(AnnotatedString(paper.title))
                                scope.launch { snackbarHostState.showSnackbar("Title copied") }
                            },
                            onOpenInFiles = { onOpenInFiles(paper) },
                            onDelete = { viewModel.deletePaper(paper) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaperRow(
    paper: PaperEntity,
    onClick: () -> Unit,
    onCopyTitle: () -> Unit,
    onOpenInFiles: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(paper.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                val subtitle = buildString {
                    paper.authors?.let { append(it) }
                    paper.year?.let { if (isNotEmpty()) append(" · "); append(it) }
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Added ${DateFormat.getDateInstance().format(Date(paper.addedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy title") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCopyTitle()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Open in Files") },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenInFiles()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
