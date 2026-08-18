package com.skydex.api.controller

import com.skydex.api.domain.UnconfirmedReason
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.models.Friendship
import com.skydex.api.models.FriendshipStatus
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistEvent
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

class FeedControllerTest : IntegrationTestBase() {

    @Test
    fun `shows my captures and my friends captures, newest first`() {
        val me = persistUser(name = "Me", email = "me@skydex.com")
        val friend = persistUser(name = "Friend", email = "friend@skydex.com")
        friendshipRepository.save(
            Friendship(
                id = null,
                requesterId = me.id!!,
                addresseeId = friend.id!!,
                status = FriendshipStatus.ACCEPTED
            )
        )

        persistEvent(me, title = "Mine (older)", capturedAt = Instant.parse("2026-08-01T10:00:00Z"))
        persistEvent(friend, title = "Theirs (newer)", capturedAt = Instant.parse("2026-08-05T10:00:00Z"))

        mockMvc.perform(get("/api/feed").header("Authorization", authHeaderFor(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].title").value("Theirs (newer)"))
            .andExpect(jsonPath("$[0].authorName").value("Friend"))
            .andExpect(jsonPath("$[1].title").value("Mine (older)"))
            .andExpect(jsonPath("$[1].authorName").value("Me"))
    }

    @Test
    fun `never shows captures from strangers`() {
        val me = persistUser(name = "Me", email = "me@skydex.com")
        val stranger = persistUser(name = "Stranger", email = "stranger@skydex.com")
        persistEvent(stranger, title = "Not for you")

        mockMvc.perform(get("/api/feed").header("Authorization", authHeaderFor(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `a pending request does not grant feed access`() {
        val me = persistUser(name = "Me", email = "me@skydex.com")
        val pending = persistUser(name = "Pending", email = "pending@skydex.com")
        friendshipRepository.save(
            Friendship(
                id = null,
                requesterId = me.id!!,
                addresseeId = pending.id!!,
                status = FriendshipStatus.PENDING
            )
        )
        persistEvent(pending, title = "Still private")

        mockMvc.perform(get("/api/feed").header("Authorization", authHeaderFor(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `paginates`() {
        val me = persistUser(name = "Me", email = "me@skydex.com")
        repeat(5) { i ->
            persistEvent(
                me,
                title = "Capture $i",
                capturedAt = Instant.parse("2026-08-0${i + 1}T10:00:00Z")
            )
        }

        mockMvc.perform(
            get("/api/feed").param("page", "0").param("size", "2")
                .header("Authorization", authHeaderFor(me))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].title").value("Capture 4"))

        mockMvc.perform(
            get("/api/feed").param("page", "2").param("size", "2")
                .header("Authorization", authHeaderFor(me))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].title").value("Capture 0"))
    }

    @Test
    fun `clamps an absurd page size instead of trusting the caller`() {
        val me = persistUser(name = "Me", email = "me@skydex.com")
        repeat(3) { i ->
            persistEvent(
                me,
                title = "Capture $i",
                capturedAt = Instant.parse("2026-08-0${i + 1}T10:00:00Z")
            )
        }

        // Above the ceiling: served, clamped to 50, so all three rows come back.
        mockMvc.perform(
            get("/api/feed").param("size", "10000").header("Authorization", authHeaderFor(me))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))

        // Below the floor: PageRequest rejects size 0 with IllegalArgumentException, so an
        // unclamped value here is a 500, not merely an odd response.
        mockMvc.perform(
            get("/api/feed").param("size", "0").header("Authorization", authHeaderFor(me))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))

        // Negative page, same reason.
        mockMvc.perform(
            get("/api/feed").param("page", "-1").header("Authorization", authHeaderFor(me))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
    }

    @Test
    fun `the feed is not public`() {
        mockMvc.perform(get("/api/feed")).andExpect(status().isUnauthorized)
    }

    /**
     * `unconfirmedReason` is for the capture's own author, not for whoever is looking at their feed
     * row. Its whole purpose is telling that person what to fix; a friend can act on none of it, and
     * seeing e.g. `MOCK_LOCATION` on someone else's row is a disclosed accusation nobody asked for.
     * `validationStatus` stays visible on the same row so the client's not-confirmed badge still
     * renders — only the reason behind it is withheld from a non-owner.
     */
    @Test
    fun `shows a friend's validation status but not the reason it was not confirmed`() {
        val me = persistUser(name = "Me", email = "me@skydex.com")
        val friend = persistUser(name = "Friend", email = "friend@skydex.com")
        friendshipRepository.save(
            Friendship(
                id = null,
                requesterId = me.id!!,
                addresseeId = friend.id!!,
                status = FriendshipStatus.ACCEPTED
            )
        )

        val flagged = persistEvent(
            friend,
            title = "Friend's flagged capture",
            validationStatus = ValidationStatus.UNCONFIRMED,
            xpAwarded = 0
        )
        flagged.unconfirmedReason = UnconfirmedReason.MOCK_LOCATION
        weatherEventRepository.save(flagged)

        val body = mockMvc.perform(get("/api/feed").header("Authorization", authHeaderFor(me)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].title").value("Friend's flagged capture"))
            .andExpect(jsonPath("$[0].validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$[0].unconfirmedReason").doesNotExist())
            .andReturn().response.contentAsString

        assertFalse(
            body.contains("MOCK_LOCATION"),
            "a friend's feed row leaked the reason their capture was flagged: $body"
        )
    }
}
