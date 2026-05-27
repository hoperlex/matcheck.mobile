package com.example.matcheckmobile.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.matcheckmobile.MatcheckApplication
import com.example.matcheckmobile.data.repository.AuthRepository
import com.example.matcheckmobile.presentation.screens.dispatch.DispatchScreen
import com.example.matcheckmobile.presentation.screens.documents.DocumentDetailsScreen
import com.example.matcheckmobile.presentation.screens.documents.DocumentsScreen
import com.example.matcheckmobile.presentation.screens.details.OperationDetailsScreen
import com.example.matcheckmobile.presentation.screens.journal.JournalScreen
import com.example.matcheckmobile.presentation.screens.login.LoginScreen
import com.example.matcheckmobile.presentation.screens.main.MainScreen
import com.example.matcheckmobile.presentation.screens.receipt.IntakeStagesScreen
import com.example.matcheckmobile.presentation.screens.receipt.IntakeUpdSelectScreen
import com.example.matcheckmobile.presentation.screens.receipt.ReceiptScreen
import com.example.matcheckmobile.presentation.screens.receipt.SavedReceiptsScreen
import com.example.matcheckmobile.presentation.screens.receipt.Stage1FormScreen
import com.example.matcheckmobile.presentation.screens.receipt.Stage2FormScreen
import com.example.matcheckmobile.presentation.screens.receipt.Stage2ListScreen
import com.example.matcheckmobile.presentation.screens.settings.SettingsScreen
import com.example.matcheckmobile.presentation.screens.splash.SplashScreen
import com.example.matcheckmobile.presentation.screens.sync.SyncQueueScreen

@Composable
fun MatcheckNavHost() {
    val context = LocalContext.current
    val container = (context.applicationContext as MatcheckApplication).container
    val navController = rememberNavController()
    // Стартуем со SPLASH: он сам решит куда вести — на MAIN (с проактивно
    // обновлённым access-токеном) или на LOGIN (если refresh мёртв).
    // Это убирает flicker «MAIN → первый запрос 401 → LOGIN».
    val startDestination = Routes.SPLASH

    LaunchedEffect(Unit) {
        container.authRepository.sessionEvents.collect { event ->
            if (event is AuthRepository.SessionEvent.LoggedOut) {
                navController.navigate(Routes.LOGIN) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onAuthenticated = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onUnauthenticated = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = {
                navController.navigate(Routes.MAIN) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            })
        }
        composable(Routes.MAIN) {
            MainScreen(
                onReceipt = { navController.navigate(Routes.INTAKE_STAGES) },
                onDispatch = { navController.navigate(Routes.DISPATCH) },
                onJournal = { navController.navigate(Routes.JOURNAL) },
                onSyncQueue = { navController.navigate(Routes.SYNC_QUEUE) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onDocuments = { navController.navigate(Routes.DOCUMENTS) },
            )
        }
        composable(Routes.INTAKE_STAGES) {
            IntakeStagesScreen(
                onBack = { navController.popBackStack() },
                onStage1 = { navController.navigate(Routes.INTAKE_UPD_SELECT) },
                onStage2 = { navController.navigate(Routes.INTAKE_STAGE2) },
            )
        }
        composable(Routes.INTAKE_STAGE2) {
            Stage2ListScreen(
                onBack = { navController.popBackStack() },
                onOpenDelivery = { id -> navController.navigate(Routes.stage2Form(id)) },
            )
        }
        composable(
            route = Routes.STAGE2_FORM,
            arguments = listOf(navArgument(Routes.ARG_DELIVERY_ID) { type = NavType.StringType }),
        ) {
            Stage2FormScreen(
                onBack = { navController.popBackStack() },
                onFinalized = {
                    // На завершении 2 этапа возвращаемся на список Stage2 —
                    // приёмка из списка уйдёт (сменит статус на confirmed_mol).
                    navController.popBackStack(Routes.INTAKE_STAGE2, inclusive = false)
                },
            )
        }
        composable(Routes.INTAKE_UPD_SELECT) {
            IntakeUpdSelectScreen(
                onBack = { navController.popBackStack() },
                onOpenWithUpd = { updId ->
                    navController.navigate(Routes.stage1FormForUpd(updId))
                },
                onOpenDraft = { draftId ->
                    navController.navigate(Routes.stage1FormForDraft(draftId))
                },
                onCreateEmpty = {
                    navController.navigate(Routes.stage1FormNew())
                },
            )
        }
        composable(
            route = Routes.STAGE1_FORM,
            arguments = listOf(
                navArgument(Routes.ARG_UPD_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Routes.ARG_DRAFT_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            Stage1FormScreen(
                onBack = { navController.popBackStack() },
                onFinalized = {
                    // Возвращаемся сразу на экран этапов — список УПД и сама форма уйдут
                    // из бэк-стека, чтобы кнопка «Назад» не открывала уже сохранённую приёмку.
                    navController.popBackStack(Routes.INTAKE_STAGES, inclusive = false)
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
