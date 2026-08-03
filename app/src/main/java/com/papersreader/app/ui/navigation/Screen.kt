package com.papersreader.app.ui.navigation

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Reader : Screen("reader/{paperId}") {
        fun route(paperId: Long) = "reader/$paperId"
    }
    data object Logs : Screen("logs")
}
