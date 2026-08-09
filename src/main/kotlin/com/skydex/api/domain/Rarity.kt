package com.skydex.api.domain

/**
 * How hard a phenomenon is to catch, and what a confirmed capture of it is worth.
 * Declared cheapest first — PhenomenonTest enforces that ordering.
 */
enum class Rarity(val xp: Int) {
    COMMON(10),
    UNCOMMON(25),
    RARE(60),
    EPIC(150),
    LEGENDARY(400)
}
