package com.skydex.api.controller

import com.skydex.api.domain.Achievement
import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.models.Friendship
import com.skydex.api.models.FriendshipStatus
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistEvent
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class ProfileControllerTest : IntegrationTestBase() {

    @Test
    fun `a new profile lists every badge as locked`() {
        val user = persistUser(name = "Rookie", email = "rookie@skydex.com")

        mockMvc.perform(get("/api/users/me/profile").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.user.name").value("Rookie"))
            .andExpect(jsonPath("$.user.password").doesNotExist())
            .andExpect(jsonPath("$.level").value(1))
            .andExpect(jsonPath("$.totalXp").value(0))
            .andExpect(jsonPath("$.confirmedCaptures").value(0))
            .andExpect(jsonPath("$.friends").value(0))
            .andExpect(jsonPath("$.unlockedBadges").value(0))
            .andExpect(jsonPath("$.totalBadges").value(Achievement.entries.size))
            .andExpect(jsonPath("$.badges.length()").value(Achievement.entries.size))
            // NOTE: not `[?(@.unlocked == true)].length()`, as the brief originally specified.
            // json-path 2.9.0's `.length()` applied after a filter that matches zero elements
            // evaluates to an empty JSONArray (`[]`), not the scalar `0` — confirmed by
            // reproducing it standalone against the exact jar on this classpath. `.value(0)`
            // then fails with "No matching value" regardless of what the endpoint returns.
            // `isEmpty()` on the bare filter result checks the same thing (no badge is
            // unlocked on a fresh profile) without going through that broken conversion.
            .andExpect(jsonPath("$.badges[?(@.unlocked == true)]").isEmpty())
    }

    @Test
    fun `three confirmed captures unlock the first two capture badges`() {
        val user = persistUser(email = "hunter@skydex.com")
        repeat(3) { i ->
            persistEvent(
                user,
                title = "Chuva $i",
                phenomenon = Phenomenon.RAIN,
                validationStatus = ValidationStatus.CONFIRMED,
                xpAwarded = Phenomenon.RAIN.rarity.xp
            )
        }

        mockMvc.perform(get("/api/users/me/profile").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.confirmedCaptures").value(3))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'FIRST_CAPTURE')].unlocked").value(true))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'THREE_CAPTURES')].unlocked").value(true))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'TEN_CAPTURES')].unlocked").value(false))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'THREE_CAPTURES')].displayName").value("Caçador de Nuvem"))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'FIRST_CAPTURE')].unlockedAt").exists())
    }

    @Test
    fun `unconfirmed captures do not unlock capture badges but do unlock the optimist`() {
        val user = persistUser(email = "optimist@skydex.com")
        repeat(5) { i ->
            persistEvent(
                user,
                title = "Granizo imaginário $i",
                phenomenon = Phenomenon.HAILSTORM,
                validationStatus = ValidationStatus.UNCONFIRMED,
                xpAwarded = 0
            )
        }

        mockMvc.perform(get("/api/users/me/profile").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.confirmedCaptures").value(0))
            .andExpect(jsonPath("$.totalCaptures").value(5))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'FIRST_CAPTURE')].unlocked").value(false))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'HAIL_SURVIVOR')].unlocked").value(false))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'WEATHER_OPTIMIST')].unlocked").value(true))
    }

    @Test
    fun `a species-specific badge unlocks only for that species`() {
        val user = persistUser(email = "chaser@skydex.com")
        persistEvent(
            user,
            phenomenon = Phenomenon.THUNDERSTORM,
            validationStatus = ValidationStatus.CONFIRMED,
            xpAwarded = Phenomenon.THUNDERSTORM.rarity.xp
        )

        mockMvc.perform(get("/api/users/me/profile").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.badges[?(@.achievement == 'THUNDER_CHASER')].unlocked").value(true))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'SNOW_SEEKER')].unlocked").value(false))
    }

    @Test
    fun `friend count feeds the network badge`() {
        val user = persistUser(email = "social@skydex.com")
        repeat(3) { i ->
            val friend = persistUser(email = "friend$i@skydex.com")
            friendshipRepository.save(
                Friendship(
                    id = null,
                    requesterId = user.id!!,
                    addresseeId = friend.id!!,
                    status = FriendshipStatus.ACCEPTED
                )
            )
        }

        mockMvc.perform(get("/api/users/me/profile").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.friends").value(3))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'WEATHER_NETWORK')].unlocked").value(true))
    }

    @Test
    fun `awarding is idempotent across repeated reads`() {
        val user = persistUser(email = "repeat@skydex.com")
        persistEvent(
            user,
            phenomenon = Phenomenon.RAIN,
            validationStatus = ValidationStatus.CONFIRMED,
            xpAwarded = Phenomenon.RAIN.rarity.xp
        )

        repeat(3) {
            mockMvc.perform(get("/api/users/me/profile").header("Authorization", authHeaderFor(user)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.unlockedBadges").value(1))
        }

        assertEquals(1, userBadgeRepository.findByUserId(user.id!!).size)
    }

    @Test
    fun `one user's badges never leak into another's profile`() {
        val mine = persistUser(email = "mine@skydex.com")
        val theirs = persistUser(email = "theirs@skydex.com")
        persistEvent(
            theirs,
            phenomenon = Phenomenon.SNOW,
            validationStatus = ValidationStatus.CONFIRMED,
            xpAwarded = Phenomenon.SNOW.rarity.xp
        )

        mockMvc.perform(get("/api/users/me/profile").header("Authorization", authHeaderFor(theirs)))
            .andExpect(status().isOk)

        mockMvc.perform(get("/api/users/me/profile").header("Authorization", authHeaderFor(mine)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unlockedBadges").value(0))
            .andExpect(jsonPath("$.badges[?(@.achievement == 'SNOW_SEEKER')].unlocked").value(false))
    }

    @Test
    fun `refuses an anonymous request`() {
        mockMvc.perform(get("/api/users/me/profile"))
            .andExpect(status().isUnauthorized)
    }
}
