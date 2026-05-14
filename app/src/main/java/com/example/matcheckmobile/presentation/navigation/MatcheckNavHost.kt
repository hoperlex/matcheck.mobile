package com.example.matcheckmobile.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.matcheckmobile.presentation.screens.dispatch.DispatchScreen
import com.example.matcheckmobile.presentation.screens.documents.DocumentDetailsScreen
import com.example.matcheckmobile.presentation.screens.documents.DocumentsScreen
import com.example.matcheckmobile.presentation.screens.details.OperationDetailsScreen
import com.example.matcheckmobile.presentation.screens.journal.JournalScreen
import com.example.matcheckmobile.presentation.screens.login.LoginScreen
import com.example.matcheckmobile.presentation.screens.main.MainScreen
import com.example.matcheckmobile.presentation.screens.receipt.ReceiptScreen
import com.example.matcheckmobile.presentation.screens.settings.SettingsScreen
import com.example.matcheckmobile.presentation.screens.sync.SyncQueueScreen

@Composable
fun MatcheckNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = {
                navController.navigate(Routes.MAIN) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }
        composable(Routes.MAIN) {
            MainScreen(
                onReceipt = { navController.navigate(Routes.RECEIPT) },
                onDispatch = { navController.navigate(Routes.DISPATCH) },
                onJournal = { navController.navigate(Routes.JOURNAL) },
                onSyncQueue = { navController.navigate(Routes.SYNC_QUEUE) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onDocuments = { navController.navigate(Routes.DOCUMENTS) },
            )
        }
        composable(Routes.RECEIPT) {
            ReceiptScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(Routes.DISPATCH) {
            DispatchScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(Routes.JOURNAL) {
            JournalScreen(
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(Routes.operationDetails(id)) },
            )
        }
        composable(Routes.SYNC_QUEUE) {
            SyncQueueScreen(
                onBack = { navController.popBackStack() },
                onOpenOperation = { id -> navController.navigate(Routes.operationDetails(id)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DOCUMENTS) {
            DocumentsScreen(
                onBack = { navController.popBackStack() },
                onOpen = { id -> navController.navigate(Routes.documentDetails(id)) },
            )
        }
        composable(
            route = Routes.OPERATION_DETAILS,
            arguments = listOf(navArgument(Routes.ARG_OPERATION_ID) { type = NavType.StringType }),
        ) {
            OperationDetailsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.DOCUMENT_DETAILS,
            arguments = listOf(navArgument(Routes.ARG_DOCUMENT_ID) { type = NavType.StringType }),
        ) {
            DocumentDetailsScreen(onBack = { navController.popBackStack() })
        }
    }
}
