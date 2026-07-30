package com.papersreader.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                            onDelete = { viewModel.deletePaper(paper) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaperRow(paper: PaperEntity, onClick: () -> Unit, onDelete: () -> Unit) {
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}
