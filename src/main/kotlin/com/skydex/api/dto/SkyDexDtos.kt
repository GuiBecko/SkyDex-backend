package com.skydex.api.dto

import java.time.Instant

data class SkyDexEntryResponse(
    val phenomenon: String,
    val displayName: String,
    val rarity: String,
    val xpPerCapture: Int,
    val captured: Boolean,
    val captureCount: Int,
    val firstCapturedAt: Instant?
)

data class SkyDexResponse(
    val level: Int,
    val totalXp: Int,
    val xpToNextLevel: Int,
    val capturedSpecies: Int,
    val totalSpecies: Int,
    val entries: List<SkyDexEntryResponse>
)
