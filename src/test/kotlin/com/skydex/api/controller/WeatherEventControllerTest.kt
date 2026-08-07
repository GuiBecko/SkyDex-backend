package com.skydex.api.controller

import com.skydex.api.dto.CreateWeatherEventRequest
import com.skydex.api.models.User
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistEvent
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

class WeatherEventControllerTest : IntegrationTestBase() {

    private lateinit var testUser: User
    private lateinit var authHeader: String

    // Before each test, create a user to associate with the events and a bearer token for it
    @BeforeEach
    fun setUpFixtures() {
        testUser = persistUser(name = "Test Pilot", email = "pilot@skydex.com")
        authHeader = authHeaderFor(testUser)
    }

    @Test
    fun `registers a new event and returns 201 with a generated id`() {
        val request = CreateWeatherEventRequest(
            title = "Aurora Borealis",
            description = "Bright green lights in the night sky.",
            photoUrl = "https://photo-link.com/aurora.jpg"
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.title").value("Aurora Borealis"))
            .andExpect(jsonPath("$.userId").value(testUser.id.toString()))
    }

    @Test
    fun `lists only the current user's events, most recent first`() {
        val now = Instant.now()
        persistEvent(
            owner = testUser,
            title = "Aurora Borealis",
            description = "lights",
            photoUrl = "http://photo1.jpg",
            capturedAt = now.minusSeconds(3600)
        )
        persistEvent(
            owner = testUser,
            title = "Eclipse",
            description = "lunar eclipse",
            photoUrl = "http://photo2.jpg",
            capturedAt = now
        )

        // Another user's event must never leak into this user's "mine" listing.
        val otherUser = persistUser(name = "Other Pilot", email = "other@skydex.com")
        persistEvent(owner = otherUser, title = "Hail", description = "not mine", photoUrl = "http://other.jpg")

        mockMvc.perform(
            get("/api/events/mine")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].title").value("Eclipse"))
            .andExpect(jsonPath("$[0].authorName").value("Test Pilot"))
            .andExpect(jsonPath("$[1].title").value("Aurora Borealis"))
            .andExpect(jsonPath("$[1].authorName").value("Test Pilot"))
    }

    @Test
    fun `finds an event by id and returns 200`() {
        val event = persistEvent(owner = testUser, title = "Aurora Borealis", description = "lights", photoUrl = "http://photo1.jpg")

        mockMvc.perform(
            get("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(event.id.toString()))
            .andExpect(jsonPath("$.title").value("Aurora Borealis"))
    }

    @Test
    fun `getById reports the event's real author, not the caller`() {
        val owner = persistUser(name = "Real Owner", email = "real-owner@skydex.com")
        val caller = persistUser(name = "Curious Caller", email = "curious-caller@skydex.com")
        val event = persistEvent(owner = owner, title = "Someone else's storm")

        mockMvc.perform(
            get("/api/events/{id}", event.id!!)
                .header("Authorization", authHeaderFor(caller))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(owner.id.toString()))
            .andExpect(jsonPath("$.authorName").value("Real Owner"))
    }

    @Test
    fun `updates an existing event and returns 200`() {
        val event = persistEvent(owner = testUser, title = "Old Title", description = "Old description", photoUrl = "url1.jpg")

        val request = CreateWeatherEventRequest(
            title = "Tornado Confirmed",
            description = "Tornado touched the ground",
            photoUrl = "url2.jpg"
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Tornado Confirmed"))
            .andExpect(jsonPath("$.photoUrl").value("url2.jpg"))
    }

    @Test
    fun `deletes an existing event and returns 204 No Content`() {
        val event = persistEvent(owner = testUser, title = "Event to delete", description = "...", photoUrl = "url.jpg")

        mockMvc.perform(
            delete("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
        )
            .andExpect(status().isNoContent)

        val stillExists = weatherEventRepository.existsById(event.id!!)
        assert(!stillExists)
    }

    @Test
    fun `returns 404 Not Found when looking up an id that does not exist`() {
        val unknownId = UUID.randomUUID()

        mockMvc.perform(
            get("/api/events/{id}", unknownId)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `refuses to update an event owned by another user`() {
        val owner = persistUser(email = "owner@skydex.com")
        val intruder = persistUser(email = "intruder@skydex.com")
        val event = persistEvent(owner, title = "Owner's storm")

        val payload = CreateWeatherEventRequest(
            title = "Hijacked",
            description = "Not mine",
            photoUrl = "http://localhost:8080/api/photos/x.jpg"
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeaderFor(intruder))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("You can only modify your own captures"))

        val unchanged = weatherEventRepository.findById(event.id!!).orElseThrow()
        assertEquals("Owner's storm", unchanged.title)
    }

    @Test
    fun `refuses to delete an event owned by another user`() {
        val owner = persistUser(email = "owner2@skydex.com")
        val intruder = persistUser(email = "intruder2@skydex.com")
        val event = persistEvent(owner)

        mockMvc.perform(
            delete("/api/events/{id}", event.id!!)
                .header("Authorization", authHeaderFor(intruder))
        )
            .andExpect(status().isForbidden)

        assertTrue(weatherEventRepository.existsById(event.id!!))
    }

    @Test
    fun `returns 401 rather than 500 for a malformed token`() {
        mockMvc.perform(
            get("/api/events/mine")
                .header("Authorization", "Bearer not-a-real-jwt")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("Authentication required"))
    }

    @Test
    fun `returns 401 when no token is supplied`() {
        mockMvc.perform(get("/api/events/mine"))
            .andExpect(status().isUnauthorized)
    }
}
