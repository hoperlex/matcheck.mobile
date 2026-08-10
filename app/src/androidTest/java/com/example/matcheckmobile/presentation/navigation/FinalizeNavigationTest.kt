package com.example.matcheckmobile.presentation.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import com.example.matcheckmobile.monitoring.IncidentRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Уход с финализированной формы. Раньше результат popBackStack игнорировался:
 * если маршрута не оказывалось в стеке, инспектор оставался на уже сохранённой
 * форме под оверлеем, который перехватывал весь ввод.
 */
class FinalizeNavigationTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeRecorder : IncidentRecorder {
        val events = mutableListOf<Map<String, Any?>>()
        override fun record(kind: String, vararg fields: Pair<String, Any?>) {
            events += mapOf("kind" to kind) + fields.toMap()
        }
        fun lastNavResult() = events.last { it["kind"] == "finalize_nav_result" }
    }

    private lateinit var nav: TestNavHostController

    private fun setUpGraph() {
        rule.setContent {
            nav = TestNavHostController(LocalContext.current)
            nav.navigatorProvider.addNavigator(ComposeNavigator())
            NavHost(navController = nav, startDestination = Routes.MAIN) {
                composable(Routes.MAIN) {}
                composable(Routes.LOGIN) {}
                composable(Routes.INTAKE_STAGES) {}
                composable(Routes.INTAKE_UPD_SELECT) {}
                composable(Routes.STAGE1_FORM) {}
            }
        }
    }

    /** Штатный путь: MAIN → этапы → выбор УПД → форма. */
    private fun openForm() = rule.runOnIdle {
        nav.navigate(Routes.INTAKE_STAGES)
        nav.navigate(Routes.INTAKE_UPD_SELECT)
        nav.navigate(Routes.stage1FormNew())
    }

    private fun backStackRoutes(): List<String> =
        nav.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun маршрут_в_стеке_обычный_pop() {
        val recorder = FakeRecorder()
        setUpGraph()
        openForm()

        rule.runOnIdle {
            nav.finishFinalizedForm(
                target = Routes.INTAKE_STAGES,
                stage = "intake_stage1",
                eventId = 1L,
                isAuthenticated = { true },
                recorder = recorder,
            )
        }

        rule.runOnIdle {
            assertEquals(Routes.INTAKE_STAGES, nav.currentDestination?.route)
            // Форма и выбор УПД ушли из стека — Back не должен открывать
            // уже сохранённую приёмку.
            assertFalse(backStackRoutes().contains(Routes.STAGE1_FORM))
            assertFalse(backStackRoutes().contains(Routes.INTAKE_UPD_SELECT))
            val result = recorder.lastNavResult()
            assertEquals(true, result["popped"])
            assertEquals(Routes.INTAKE_STAGES, result["landed"])
        }
    }

    @Test
    fun стек_снесён_сессия_жива_форма_всё_равно_уходит() {
        val recorder = FakeRecorder()
        setUpGraph()
        openForm()

        // Так стек чистит logout-ветка в MatcheckNavHost.
        rule.runOnIdle { nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }

        rule.runOnIdle {
            nav.finishFinalizedForm(
                target = Routes.INTAKE_STAGES,
                stage = "intake_stage1",
                eventId = 2L,
                isAuthenticated = { true },
                recorder = recorder,
            )
        }

        rule.runOnIdle {
            assertEquals(Routes.INTAKE_STAGES, nav.currentDestination?.route)
            // Под целевым экраном должна лежать главная, иначе Back закроет приложение.
            assertTrue(backStackRoutes().contains(Routes.MAIN))
            assertFalse(backStackRoutes().contains(Routes.STAGE1_FORM))
            val result = recorder.lastNavResult()
            assertEquals(false, result["popped"])
            assertEquals(Routes.INTAKE_STAGES, result["landed"])
        }
    }

    @Test
    fun стек_снесён_сессии_нет_итог_LOGIN() {
        val recorder = FakeRecorder()
        setUpGraph()
        openForm()

        rule.runOnIdle { nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }

        rule.runOnIdle {
            nav.finishFinalizedForm(
                target = Routes.INTAKE_STAGES,
                stage = "intake_stage1",
                eventId = 3L,
                isAuthenticated = { false },
                recorder = recorder,
            )
        }

        rule.runOnIdle {
            assertEquals(Routes.LOGIN, nav.currentDestination?.route)
            // После logout авторизованные экраны восстанавливать нельзя.
            assertFalse(backStackRoutes().contains(Routes.INTAKE_STAGES))
            assertFalse(backStackRoutes().contains(Routes.MAIN))
            val result = recorder.lastNavResult()
            assertEquals(false, result["popped"])
            assertEquals(Routes.LOGIN, result["landed"])
        }
    }
}
