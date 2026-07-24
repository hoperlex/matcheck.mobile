package com.example.matcheckmobile.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPolicyTest {

    @Test
    fun confirmedMol_isTerminal() {
        assertTrue(StatusPolicy.isTerminal("confirmed_mol"))
        assertTrue(StatusPolicy.isTerminal(StatusPolicy.CONFIRMED_MOL))
    }

    @Test
    fun nonTerminalStatuses_areNotTerminal() {
        assertFalse(StatusPolicy.isTerminal("filled"))
        assertFalse(StatusPolicy.isTerminal("draft"))
        assertFalse(StatusPolicy.isTerminal("not_filled"))
        assertFalse(StatusPolicy.isTerminal("shipped"))
        assertFalse(StatusPolicy.isTerminal(null))
        assertFalse(StatusPolicy.isTerminal(""))
    }
}
