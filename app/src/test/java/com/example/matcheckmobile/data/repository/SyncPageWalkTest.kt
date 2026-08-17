package com.example.matcheckmobile.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обход страниц дельта-синхронизации.
 *
 * Главное, что здесь проверяется, — курсор НЕ сохраняется, пока сервер сообщает
 * о продолжении. Это единственная защита от потери хвоста: сдвинутый в середине
 * снимка курсор навсегда пропускает всё, что не успело приехать.
 */
class SyncPageWalkTest {

    @Test
    fun `середина снимка - курсор не сохраняется и обход продолжается`() {
        val step = decidePageStep(nextPageToken = "tok-2", hasMoreByPageSize = false)

        assertEquals("tok-2", step.pageToken)
        assertFalse("курсор в середине снимка сдвигать нельзя", step.commitCursor)
        assertFalse(step.done)
    }

    @Test
    fun `полная страница в середине снимка ничего не меняет`() {
        // Полнота страницы — признак СТАРОГО контракта. Пока сервер шлёт токен,
        // он главнее: иначе клиент сдвинул бы курсор, имея явное «есть ещё».
        val step = decidePageStep(nextPageToken = "tok-3", hasMoreByPageSize = true)

        assertEquals("tok-3", step.pageToken)
        assertFalse(step.commitCursor)
        assertFalse(step.done)
    }

    @Test
    fun `последняя страница снимка - курсор сохраняется, обход закончен`() {
        val step = decidePageStep(nextPageToken = null, hasMoreByPageSize = false)

        assertEquals(null, step.pageToken)
        assertTrue(step.commitCursor)
        assertTrue(step.done)
    }

    @Test
    fun `старый сервер с полной страницей - курсор сохраняется, проход повторяется`() {
        // Прежнее поведение обязано сохраниться дословно: сервер без группового
        // режима nextPageToken не присылает вовсе, и «есть ещё» определяется
        // только полнотой страницы.
        val step = decidePageStep(nextPageToken = null, hasMoreByPageSize = true)

        assertEquals(null, step.pageToken)
        assertTrue(step.commitCursor)
        assertFalse("полная страница у старого сервера означает продолжение", step.done)
    }
}
