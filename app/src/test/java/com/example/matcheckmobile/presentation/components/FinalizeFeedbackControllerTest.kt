package com.example.matcheckmobile.presentation.components

import com.example.matcheckmobile.monitoring.IncidentRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контроллер success-оверлея. Главное, что проверяем — оверлей всегда гаснет
 * ровно один раз и никогда не остаётся висеть: именно залипший оверлей запирал
 * инспектора на финализированной форме.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FinalizeFeedbackControllerTest {

    private class FakeRecorder : IncidentRecorder {
        val events = mutableListOf<Map<String, Any?>>()
        override fun record(kind: String, vararg fields: Pair<String, Any?>) {
            events += mapOf("kind" to kind) + fields.toMap()
        }
        fun hides() = events.filter { it["kind"] == "overlay_hidden" }
    }

    @Test
    fun `show гаснет сам через holdMs`() = runTest {
        val recorder = FakeRecorder()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controller = FinalizeFeedbackController(scope, recorder)

        controller.show("intake_stage1", holdMs = 900L)
        assertNotNull("оверлей должен быть виден сразу", controller.state.value)

        advanceTimeBy(899L)
        assertNotNull("до истечения holdMs оверлей ещё виден", controller.state.value)

        advanceUntilIdle()
        assertNull("после holdMs оверлей обязан погаснуть", controller.state.value)
        assertEquals(listOf("timer"), recorder.hides().map { it["reason"] })
    }

    @Test
    fun `повторный show перезапускает отсчёт, а старый таймер не гасит новый показ`() = runTest {
        val recorder = FakeRecorder()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controller = FinalizeFeedbackController(scope, recorder)

        val first = controller.show("intake_stage1", holdMs = 900L)
        advanceTimeBy(800L)
        val second = controller.show("intake_stage2", holdMs = 900L)

        // Момент, когда истёк бы срок первого таймера. Здесь его снимает ещё и
        // cancel(); поколение страхует другой случай — когда таймер уже прошёл
        // delay и вот-вот присвоит состояние, а show/hide прилетает с другого
        // потока. Такое окно однопоточным планировщиком не воспроизвести,
        // поэтому проверяем наблюдаемый контракт: побеждает последний показ.
        advanceTimeBy(150L)
        val stillShown = controller.state.value
        assertNotNull("первый таймер не имеет права гасить второй показ", stillShown)
        assertEquals(second, stillShown!!.eventId)
        assertEquals("intake_stage2", stillShown.stage)

        advanceUntilIdle()
        assertNull(controller.state.value)
        // Ровно одно скрытие — второго показа. Первый показ был вытеснен.
        assertEquals(listOf(second), recorder.hides().map { it["eventId"] })
        assertEquals(1L, first)
    }

    @Test
    fun `hide обесценивает живой таймер и не даёт двойной записи`() = runTest {
        val recorder = FakeRecorder()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controller = FinalizeFeedbackController(scope, recorder)

        val id = controller.show("intake_stage1", holdMs = 900L)
        advanceTimeBy(100L)
        controller.hide("user")
        assertNull("ручное закрытие гасит сразу", controller.state.value)

        advanceUntilIdle()
        assertNull(controller.state.value)
        assertEquals(listOf("user"), recorder.hides().map { it["reason"] })
        assertEquals(listOf(id), recorder.hides().map { it["eventId"] })
    }

    @Test
    fun `hide без показа ничего не пишет`() = runTest {
        val recorder = FakeRecorder()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val controller = FinalizeFeedbackController(scope, recorder)

        controller.hide("user")
        advanceUntilIdle()

        assertNull(controller.state.value)
        assertEquals(emptyList<Map<String, Any?>>(), recorder.hides())
    }
}
