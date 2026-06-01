package com.autovice.reader.ui.navigation

sealed class Screen(val route: String) {
    object Library : Screen("library")

    object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: String) = "reader/$bookId"
    }

    object Settings : Screen("settings")
}
