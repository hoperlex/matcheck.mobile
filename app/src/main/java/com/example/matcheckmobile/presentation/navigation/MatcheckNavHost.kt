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
import com.example.matcheckmobile.presentation.screens.receipt.IntakeUpdSelectScreen
import com.example.matcheckmobile.presentation.screens.receipt.ReceiptScreen
import com.example.matcheckmobile.presentation.screens.receipt.SavedReceiptsScreen
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
                onReceipt = { navController.navigate(Routes.INTAKE_UPD_SELECT) },
                onDispatch = { navController.navigate(Routes.DISPATCH) },
                onJournal = { navController.navigate(Routes.JOURNAL) },
                onSyncQueue = { navController.navigate(Routes.SYNC_QUEUE) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onDocuments = { navController.navigate(Routes.DOCUMENTS) },
            )
        }
        composable(Routes.INTAKE_UPD_SELECT) {
            IntakeUpdSelectScreen(
                onBack = { navController.popBackStack() },
                onOpenWithUpd = { updId ->
                    navController.navigate(Routes.receiptForUpd(updId))
                },
                onCreateEmpty = {
                    navController.navigate(Routes.receiptNew())
                },
                onOpenSavedList = {
                    navController.navigate(Routes.SAVED_RECEIPTS)
                },
            )
        }
        composable(Routes.SAVED_RECEIPTS) {
            SavedReceiptsScreen(
                onBack = { navController.popBackStack() },
                onOpenReceipt = { sessionId ->
                    navController.navigate(Routes.receiptForSession(sessionId))
                },
            )
        }
        composable(
            route = Routes.RECEIPT,
            arguments = listOf(
                navArgument(Routes.ARG_UPD_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Routes.ARG_SESSION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
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
                onOpenDocument = { id -> navController.navigate(Routes.documentDetails(id)) },
                onOpenReceipt = { id -> navController.navigate(Routes.receiptForSession(id)) },
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
