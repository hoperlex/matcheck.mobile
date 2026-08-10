package com.example.matcheckmobile.presentation.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Аварийные выходы из success-оверлея. До фикса он был создан с
 * dismissOnBackPress=false и dismissOnClickOutside=false, то есть перехватывал
 * весь ввод и не закрывался ничем — любой сбой таймера запирал инспектора
 * на финализированной форме до перезапуска приложения.
 */
class FinalizeSuccessOverlayTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun тап_по_карточке_закрывает() {
        var dismissed = false
        rule.setContent { FinalizeSuccessOverlay(onDismiss = { dismissed = true }) }

        rule.onNodeWithTag(SUCCESS_OVERLAY_TAG).performClick()
        rule.waitForIdle()

        assertTrue("тап по самой галочке обязан закрывать оверлей", dismissed)
    }

    @Test
    fun системный_back_закрывает() {
        var dismissed = false
        rule.setContent { FinalizeSuccessOverlay(onDismiss = { dismissed = true }) }
        rule.waitForIdle()

        Espresso.pressBack()
        rule.waitForIdle()

        assertTrue("Back обязан закрывать оверлей", dismissed)
    }
}
