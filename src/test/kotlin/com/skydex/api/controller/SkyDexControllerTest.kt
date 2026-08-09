package com.skydex.api.controller

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistEvent
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

class SkyDexControllerTest : IntegrationTestBase() {

    @Test
    fun `an empty collection lists every species as uncaptured`() {
        val user = persistUser(email = "rookie@skydex.com")

        mockMvc.perform(get("/api/skydex").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.level").value(1))
            .andExpect(jsonPath("$.totalXp").value(0))
            .andExpect(jsonPath("$.capturedSpecies").value(0))
            .andExpect(jsonPath("$.totalSpecies").value(Phenomenon.entries.size))
            .andExpect(jsonPath("$.entries.length()").value(Phenomenon.entries.size))
            // Not `.length()`: jayway JsonPath chains a function after an indefinite (filtered)
            // path by evaluating it over each match, so a filter with zero matches yields an
            // empty JSON array rather than the integer 0, and `.value(0)` then fails as
            // "No matching value". Asserting emptiness on the filtered array itself sidesteps that.
            .andExpect(jsonPath("$.entries[?(@.captured == true)]").isEmpty())
    }

    @Test
    fun `counts confirmed captures per species and sums their xp`() {
        val user = persistUser(email = "veteran@skydex.com")
        val earlier = Instant.parse("2026-08-01T10:00:00Z")
        val later = Instant.parse("2026-08-05T10:00:00Z")

        persistEvent(
            user, title = "Storm 1", capturedAt = later,
            phenomenon = Phenomenon.THUNDERSTORM,
            validationStatus = ValidationStatus.CONFIRMED,
            xpAwarded = Phenomenon.THUNDERSTORM.rarity.xp
        )
        persistEvent(
            user, title = "Storm 2", capturedAt = earlier,
            phenomenon = Phenomenon.THUNDERSTORM,
            validationStatus = ValidationStatus.CONFIRMED,
            xpAwarded = Phenomenon.THUNDERSTORM.rarity.xp
        )
        persistEvent(
            user, title = "Fog", capturedAt = later,
            phenomenon = Phenomenon.FOG,
            validationStatus = ValidationStatus.CONFIRMED,
            xpAwarded = Phenomenon.FOG.rarity.xp
        )

        mockMvc.perform(get("/api/skydex").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalXp").value(60 + 60 + 25))
            .andExpect(jsonPath("$.level").value(2))
            .andExpect(jsonPath("$.capturedSpecies").value(2))
            .andExpect(jsonPath("$.entries[?(@.phenomenon == 'THUNDERSTORM')].captureCount").value(2))
            .andExpect(
                jsonPath("$.entries[?(@.phenomenon == 'THUNDERSTORM')].firstCapturedAt")
                    .value("2026-08-01T10:00:00Z")
            )
    }

    @Test
    fun `unconfirmed captures do not unlock a species`() {
        val user = persistUser(email = "liar@skydex.com")
        persistEvent(
            user, title = "Alleged hail",
            phenomenon = Phenomenon.HAILSTORM,
            validationStatus = ValidationStatus.UNCONFIRMED,
            xpAwarded = 0
        )

        mockMvc.perform(get("/api/skydex").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capturedSpecies").value(0))
            .andExpect(jsonPath("$.totalXp").value(0))
            .andExpect(jsonPath("$.entries[?(@.phenomenon == 'HAILSTORM')].captured").value(false))
    }

    @Test
    fun `one user's captures never appear in another user's collection`() {
        val mine = persistUser(email = "mine@skydex.com")
        val theirs = persistUser(email = "theirs@skydex.com")
        persistEvent(
            theirs, phenomenon = Phenomenon.SNOW,
            validationStatus = ValidationStatus.CONFIRMED,
            xpAwarded = Phenomenon.SNOW.rarity.xp
        )

        mockMvc.perform(get("/api/skydex").header("Authorization", authHeaderFor(mine)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capturedSpecies").value(0))
            .andExpect(jsonPath("$.totalXp").value(0))
    }
}
