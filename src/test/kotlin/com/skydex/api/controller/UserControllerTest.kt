package com.skydex.api.controller

import com.skydex.api.dto.UpdateProfileRequest
import com.skydex.api.repositories.UserRepository
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistEvent
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

class UserControllerTest : IntegrationTestBase() {

    /**
     * A spy, not a mock: every method runs for real unless a test stubs it. It replaces the single
     * `UserRepository` bean, so the `userRepository` the base class autowires is this same object —
     * this field exists only to have something to hang `doAnswer` on. It lets one test below inject
     * a concurrent trail write at a precise point inside a request; nothing else here behaves
     * differently for its presence.
     */
    @SpyBean
    private lateinit var userRepositorySpy: UserRepository

    @Test
    fun `finds the authenticated user and returns 200`() {
        val user = persistUser(name = "Guilherme", email = "busca@test.com")

        mockMvc.perform(
            get("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(user.id.toString()))
            .andExpect(jsonPath("$.name").value("Guilherme"))
    }

    @Test
    fun `updates the authenticated user's profile and returns 200`() {
        val user = persistUser(name = "Old Name", email = "old@test.com")

        val request = UpdateProfileRequest(name = "Guilherme", email = "new@test.com")

        mockMvc.perform(
            put("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Guilherme"))
            .andExpect(jsonPath("$.email").value("new@test.com"))
    }

    @Test
    fun `deletes the authenticated user and returns 204 No Content`() {
        val user = persistUser(name = "User to delete", email = "delete@test.com")
        persistEvent(owner = user, title = "Orphan risk", description = "Should go with the user", photoUrl = "/api/photos/orphan.jpg")

        mockMvc.perform(
            delete("/api/users/me")
                .header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isNoContent)

        val stillExists = userRepository.existsById(user.id!!)
        assert(!stillExists)

        val orphanedEvents = weatherEventRepository.findByUserIdOrderByCapturedAtDesc(user.id!!)
        assert(orphanedEvents.isEmpty())
    }

    @Test
    fun `user responses never expose the password hash`() {
        val user = persistUser(name = "Leak Check", email = "leak@skydex.com", password = "plain-text-secret")

        mockMvc.perform(
            get("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Leak Check"))
            .andExpect(jsonPath("$.email").value("leak@skydex.com"))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.authorities").doesNotExist())
            .andExpect(jsonPath("$.enabled").doesNotExist())
    }

    @Test
    fun `me returns the authenticated user`() {
        val user = persistUser(name = "Self", email = "self@skydex.com")

        mockMvc.perform(
            get("/api/users/me").header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(user.id!!.toString()))
            .andExpect(jsonPath("$.name").value("Self"))
            .andExpect(jsonPath("$.password").doesNotExist())
    }

    /**
     * The movement trail is anti-cheat state that happens to live on the user row, and a profile
     * update must not be able to move it. `updateMe` used to mutate the authenticated `User` and
     * `save` it, which writes every column back from the snapshot SecurityFilter loaded at the
     * start of the request — so a rename racing a capture restores an OLDER `last_capture_at`, and
     * an older timestamp buys a bigger reachable radius. Renaming yourself was a way to buy travel
     * budget.
     *
     * Note what this test does NOT do, because the obvious version of it is worthless: seeding a
     * trail, updating the profile and asserting the trail survived passes against the OLD code
     * too. The pre-request snapshot already contains the seeded trail, so writing it back changes
     * nothing. Only a trail that moves DURING the request can tell the two implementations apart.
     *
     * So the spy advances the trail at the one seam between the principal being loaded and the
     * profile being written: `updateMe`'s own email-uniqueness lookup. Stubbing it by the NEW email
     * keeps it distinct from the lookup `SecurityFilter` does by the old one, which is what loads
     * the stale snapshot in the first place.
     */
    @Test
    fun `a profile update does not rewind a trail that moved while it was in flight`() {
        val user = persistUser(name = "Old Name", email = "trail@skydex.com")
        val staleAt = Instant.parse("2026-08-07T14:00:00Z")
        val movedAt = Instant.parse("2026-08-08T09:30:00Z")
        userRepository.recordLastCapture(user.id!!, -30.0346, -51.2177, staleAt)

        doAnswer {
            // A capture of this user's commits while the profile update is mid-flight, leaving the
            // trail in Tokyo. The handler is holding a `User` that still says Porto Alegre.
            userRepository.recordLastCapture(user.id!!, 35.6762, 139.6503, movedAt)
            null
        }.`when`(userRepositorySpy).findByEmail("moved@skydex.com")

        mockMvc.perform(
            put("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(UpdateProfileRequest("New Name", "moved@skydex.com")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("New Name"))
            .andExpect(jsonPath("$.email").value("moved@skydex.com"))

        val after = userRepository.findById(user.id!!).orElseThrow()
        assertEquals("New Name", after.name, "the profile update did not take effect")
        assertEquals("moved@skydex.com", after.email, "the profile update did not take effect")
        // The trail must still be where the capture left it, not where the stale snapshot said.
        assertEquals(35.6762, after.lastCaptureLatitude, "the profile update rewound the trail")
        assertEquals(139.6503, after.lastCaptureLongitude, "the profile update rewound the trail")
        assertEquals(movedAt, after.lastCaptureAt, "the profile update rewound the trail's clock")
    }

    /**
     * The trail is a record of where this user has physically been. `UserResponse` maps four fields
     * explicitly so it is already excluded — this pins that, the way the password-hash test above
     * pins its own field, because the leak would arrive silently the day someone reaches for
     * reflection or a `@JsonAutoDetect`-style shortcut.
     *
     * Both the field name and the value are checked: a renamed JSON property would slip past a
     * name-only assertion.
     */
    @Test
    fun `user responses never expose the movement trail`() {
        val user = persistUser(name = "Tracked", email = "tracked@skydex.com")
        user.lastCaptureLatitude = -30.0346
        user.lastCaptureLongitude = -51.2177
        user.lastCaptureAt = Instant.parse("2026-08-07T14:00:00Z")
        userRepository.save(user)

        val body = mockMvc.perform(
            get("/api/users/me").header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastCaptureLatitude").doesNotExist())
            .andExpect(jsonPath("$.lastCaptureLongitude").doesNotExist())
            .andExpect(jsonPath("$.lastCaptureAt").doesNotExist())
            .andReturn().response.contentAsString

        assertFalse(body.contains("-30.0346"), "the response leaked the user's last known position")
        assertFalse(body.contains("lastCapture"), "the response leaked a movement-trail field")
    }

    @Test
    fun `rejects a profile update that would take another user's email`() {
        persistUser(email = "taken@skydex.com")
        val user = persistUser(email = "mover@skydex.com")

        val payload = UpdateProfileRequest(name = "Mover", email = "taken@skydex.com")

        mockMvc.perform(
            put("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("Email already registered"))
    }
}
