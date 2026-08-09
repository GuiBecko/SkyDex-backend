package com.skydex.api.domain

import kotlin.math.floor
import kotlin.math.sqrt

private const val XP_PER_LEVEL_UNIT = 100

/**
 * Level curve: level N starts at (N-1)^2 * 100 XP. Level 2 at 100, level 3 at 400,
 * level 4 at 900. Quadratic so the early levels arrive fast and the later ones mean something.
 */
fun levelFor(totalXp: Int): Int {
    if (totalXp <= 0) return 1
    return 1 + floor(sqrt(totalXp.toDouble() / XP_PER_LEVEL_UNIT)).toInt()
}

/** XP still needed to reach the next level. */
fun xpToNextLevel(totalXp: Int): Int {
    val safeXp = maxOf(totalXp, 0)
    val nextLevel = levelFor(safeXp) + 1
    val threshold = (nextLevel - 1) * (nextLevel - 1) * XP_PER_LEVEL_UNIT
    return threshold - safeXp
}
