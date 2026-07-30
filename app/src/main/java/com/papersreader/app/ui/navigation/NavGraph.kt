package com.papersreader.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.papersreader.app.ui.browser.BrowserScreen
import com.papersreader.app.ui.debug.LogsScreen
import com.papersreader.app.ui.library.LibraryScreen
import com.papersreader.app.ui.reader.ReaderScreen

private data class BottomTab(val screen: Screen, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomTabs = listOf(
    BottomTab(Screen.Library, "Library", Icons.Filled.MenuBook),
    BottomTab(Screen.Browser, "Browser", Icons.Filled.Public),
)

@Composable
fun PapersReaderNavGraph(pendingImportUri: String?, onPendingImportConsumed: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == Screen.Library.route || currentRoute == Screen.Browser.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val currentDestination = backStackEntry?.destination
                    bottomTabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Library.route) {
                LibraryScreen(
                    pendingImportUri = pendingImportUri,
                    onPendingImportConsumed = onPendingImportConsumed,
                    onOpenPaper = { paperId -> navController.navigate(Screen.Reader.route(paperId)) },
                    onOpenLogs = { navController.navigate(Screen.Logs.route) },
                )
            }
            composable(Screen.Browser.route) {
                BrowserScreen(
                    onSaveToLibrary = { navController.navigate(Screen.Library.route) },
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
                    onOpenInBrowser = { navController.navigate(Screen.Browser.route) },
                )
            }
            composable(Screen.Logs.route) {
                LogsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
