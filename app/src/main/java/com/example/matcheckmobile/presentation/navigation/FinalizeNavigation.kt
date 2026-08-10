package com.example.matcheckmobile.presentation.navigation

import androidx.navigation.NavController
import com.example.matcheckmobile.monitoring.IncidentRecorder

/**
 * Гарантированный уход с финализированной формы.
 *
 * Раньше здесь стоял голый `popBackStack(target, inclusive = false)`, результат
 * которого никто не проверял. Если он возвращал false, инспектор оставался на
 * уже сохранённой форме под success-оверлеем, который перехватывал весь ввод, —
 * выхода не было вообще, только перезапуск приложения.
 *
 * `internal`, а не `private`: androidTest в том же модуле дёргает функцию
 * напрямую. `isAuthenticated` и `recorder` приходят параметрами, чтобы тест не
 * поднимал контейнер и не ждал файловый I/O журнала.
 */
internal fun NavController.finishFinalizedForm(
    target: String,
    stage: String,
    eventId: Long,
    isAuthenticated: () -> Boolean,
    recorder: IncidentRecorder,
) {
    val popped = popBackStack(target, inclusive = false)
    if (!popped) {
        // Маршрута в стеке нет — например, его снесла logout-ветка
        // (см. MatcheckNavHost: navigate(LOGIN) { popUpTo(0) }).
        // Если сессия жива, пересобираем стек так, чтобы форма ушла
        // гарантированно, а Back вёл на главную, а не обратно на неё.
        // Если сессии уже нет, единственный безопасный итог — LOGIN:
        // восстанавливать авторизованные экраны после logout нельзя.
        if (isAuthenticated()) {
            navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } }
            navigate(target) { launchSingleTop = true }
        } else {
            navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }
    // Пишем всегда, а не только при аномалии: без записи об успешном уходе
    // последующий process_exit не с чем связать. `landed` — best-effort
    // снимок сразу после операции, а не гарантия итога.
    recorder.record(
        "finalize_nav_result",
        "eventId" to eventId,
        "stage" to stage,
        "target" to target,
        "popped" to popped,
        "landed" to currentBackStackEntry?.destination?.route,
    )
}
