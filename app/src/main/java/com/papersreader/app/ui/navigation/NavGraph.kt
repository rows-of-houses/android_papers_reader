package com.papersreader.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.papersreader.app.ui.debug.LogsScreen
import com.papersreader.app.ui.library.LibraryScreen
import com.papersreader.app.ui.reader.ReaderScreen

@Composable
fun PapersReaderNavGraph(pendingImportUri: String?, onPendingImportConsumed: () -> Unit) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Library.route) {
        composable(Screen.Library.route) {
            LibraryScreen(
                pendingImportUri = pendingImportUri,
                onPendingImportConsumed = onPendingImportConsumed,
                onOpenPaper = { paperId -> navController.navigate(Screen.Reader.route(paperId)) },
                onOpenLogs = { navController.navigate(Screen.Logs.route) },
            )
        }
        composable(
            route = Screen.Reader.route,
            arguments = listOf(androidx.navigation.navArgument("paperId") { type = androidx.navigation.NavType.LongType }),
        ) { entry ->
            val paperId = entry.arguments?.getLong("paperId") ?: return@composable
            ReaderScreen(
                paperId = paperId,
                onBack = { navController.popBackStack() },
                onOpenPaper = { newPaperId -> navController.navigate(Screen.Reader.route(newPaperId)) },
            )
        }
        composable(Screen.Logs.route) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
