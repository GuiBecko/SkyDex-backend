package com.skydex.api.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LevelsTest {

    @Test
    fun `a brand new account is level 1`() {
        assertEquals(1, levelFor(0))
        assertEquals(1, levelFor(99))
    }

    @Test
    fun `each level costs quadratically more xp`() {
        assertEquals(2, levelFor(100))
        assertEquals(2, levelFor(399))
        assertEquals(3, levelFor(400))
        assertEquals(4, levelFor(900))
        assertEquals(5, levelFor(1600))
    }

    @Test
    fun `negative xp cannot drop below level 1`() {
        assertEquals(1, levelFor(-50))
    }

    @Test
    fun `reports how much xp the next level still needs`() {
        assertEquals(100, xpToNextLevel(0))
        assertEquals(1, xpToNextLevel(99))
        assertEquals(300, xpToNextLevel(100))
    }
}
