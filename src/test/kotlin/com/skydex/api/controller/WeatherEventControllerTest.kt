package com.skydex.api.controller

import com.skydex.api.dto.CreateWeatherEventRequest
import com.skydex.api.models.User
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistEvent
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
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
            photoUrl = "/api/photos/aurora.jpg",
            latitude = -23.55,
            longitude = -46.63
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
            photoUrl = "/api/photos/photo1.jpg",
            capturedAt = now.minusSeconds(3600)
        )
        persistEvent(
            owner = testUser,
            title = "Eclipse",
            description = "lunar eclipse",
            photoUrl = "/api/photos/photo2.jpg",
            capturedAt = now
        )

        // Another user's event must never leak into this user's "mine" listing.
        val otherUser = persistUser(name = "Other Pilot", email = "other@skydex.com")
        persistEvent(owner = otherUser, title = "Hail", description = "not mine", photoUrl = "/api/photos/other.jpg")

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
        val event = persistEvent(owner = testUser, title = "Aurora Borealis", description = "lights", photoUrl = "/api/photos/photo1.jpg")

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
        val event = persistEvent(owner = testUser, title = "Old Title", description = "Old description", photoUrl = "/api/photos/url1.jpg")

        val request = CreateWeatherEventRequest(
            title = "Tornado Confirmed",
            description = "Tornado touched the ground",
            photoUrl = "/api/photos/url2.jpg",
            latitude = -23.55,
            longitude = -46.63
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Tornado Confirmed"))
            .andExpect(jsonPath("$.photoUrl").value("http://localhost:8080/api/photos/url2.jpg"))
    }

    /**
     * The whole point of storing `photo_url` relative, pinned at both ends in one pass: upload a
     * photo the way the app does, hand the returned string straight back to `POST /api/events`,
     * then check that what landed in the row is host-free while what every read endpoint hands a
     * client is fetchable.
     *
     * A row is immutable once written, so an absolute URL persisted here would outlive the host it
     * names — a new DHCP lease or a real deployment and every historical capture points at bytes
     * nobody serves. The base URL under test comes from `application-test.properties`.
     */
    @Test
    fun `stores a photo url relative but returns it absolute`() {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val part = MockMultipartFile("file", "storm.jpg", MediaType.IMAGE_JPEG_VALUE, jpegBytes)

        val uploaded = mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeader)
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val relativeUrl = objectMapper.readTree(uploaded).get("photoUrl").asText()
        assertTrue(relativeUrl.startsWith("/api/photos/"), "upload returned $relativeUrl")

        val request = CreateWeatherEventRequest(
            title = "Supercell",
            description = "Rotating wall cloud",
            // Exactly what the upload returned — the client persists what it was given.
            photoUrl = relativeUrl,
            latitude = -23.55,
            longitude = -46.63
        )

        val created = mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.photoUrl").value("http://localhost:8080$relativeUrl"))
            .andReturn().response.contentAsString
        val id = UUID.fromString(objectMapper.readTree(created).get("id").asText())

        // The end that matters most: nothing host-specific reached the database.
        val stored = weatherEventRepository.findById(id).orElseThrow()
        assertEquals(relativeUrl, stored.photoUrl)
        assertFalse(stored.photoUrl.contains("://"), "a host was persisted: ${stored.photoUrl}")

        // ...and every read endpoint still hands the client something it can actually fetch.
        mockMvc.perform(get("/api/events/{id}", id).header("Authorization", authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photoUrl").value("http://localhost:8080$relativeUrl"))

        mockMvc.perform(get("/api/events/mine").header("Authorization", authHeader))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].photoUrl").value("http://localhost:8080$relativeUrl"))
    }

    /** The fourth response site. Update composes on the way out and still persists relative. */
    @Test
    fun `update returns an absolute photo url while persisting the relative one`() {
        val event = persistEvent(owner = testUser, title = "Old", description = "Old", photoUrl = "/api/photos/old.jpg")

        val request = CreateWeatherEventRequest(
            title = "New",
            description = "New description",
            photoUrl = "/api/photos/new.jpg",
            latitude = -23.55,
            longitude = -46.63
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photoUrl").value("http://localhost:8080/api/photos/new.jpg"))

        assertEquals("/api/photos/new.jpg", weatherEventRepository.findById(event.id!!).orElseThrow().photoUrl)
    }

    @Test
    fun `deletes an existing event and returns 204 No Content`() {
        val event = persistEvent(owner = testUser, title = "Event to delete", description = "...", photoUrl = "/api/photos/url.jpg")

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
            photoUrl = "/api/photos/x.jpg",
            latitude = -23.55,
            longitude = -46.63
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

    @Test
    fun `stores the coordinates and stamps the capture time on the server`() {
        val user = persistUser(email = "geo@skydex.com")
        val before = Instant.now()

        val payload = CreateWeatherEventRequest(
            title = "Tempestade",
            description = "Raios sobre o bairro",
            photoUrl = "/api/photos/storm.jpg",
            latitude = -30.0346,
            longitude = -51.2177
        )

        val body = mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.latitude").value(-30.0346))
            .andExpect(jsonPath("$.longitude").value(-51.2177))
            .andExpect(jsonPath("$.authorName").value("Test Pilot"))
            .andReturn().response.contentAsString

        // The client never sends a capture time, so the only thing that can be asserted is that
        // the server chose one, and chose it now. This is the whole anti-backdating property:
        // if a `capturedAt` field is ever added back to the request, this test still passes but
        // stops meaning anything — so the request DTO having no such field is what protects it.
        val stamped = Instant.parse(objectMapper.readTree(body).get("capturedAt").asText())
        assertTrue(!stamped.isBefore(before.truncatedTo(ChronoUnit.MILLIS)))
        assertTrue(!stamped.isAfter(Instant.now()))
    }

    @Test
    fun `rejects a latitude outside the valid range`() {
        val user = persistUser(email = "badgeo@skydex.com")

        val payload = CreateWeatherEventRequest(
            title = "Impossible",
            description = "Off the planet",
            photoUrl = "/api/photos/x.jpg",
            latitude = 120.0,
            longitude = 0.0
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("latitude: must be between -90 and 90"))
    }

    @Test
    fun `ignores coordinates supplied on update`() {
        val user = persistUser(email = "pinmover@skydex.com")
        val event = persistEvent(owner = user, latitude = -30.0346, longitude = -51.2177)

        // The mirror image of backdating. Task 12 scores a capture against the weather at an
        // instant AND a place, so a movable pin is a movable verdict: create now, look up where a
        // storm is happening at this frozen instant, PUT the coordinates there, collect the badge.
        val payload = CreateWeatherEventRequest(
            title = "Editado",
            description = "So o texto mudou",
            photoUrl = "/api/photos/x.jpg",
            latitude = 35.6762,
            longitude = 139.6503
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Editado"))
            .andExpect(jsonPath("$.latitude").value(-30.0346))
            .andExpect(jsonPath("$.longitude").value(-51.2177))
    }

    @Test
    fun `accepts a longitude that is only valid on the longitude scale`() {
        val user = persistUser(email = "meridian@skydex.com")

        // 120 deg E is a real place (western Australia) and the ONLY value that catches the bug
        // this pair of tests exists for: latitude's bounds copy-pasted onto longitude. A rejection
        // test cannot catch it — 200 is outside both +-90 and +-180, and the message string is a
        // literal rather than something derived from the annotation, so it reads "-180 and 180"
        // either way. Acceptance is what discriminates.
        val payload = CreateWeatherEventRequest(
            title = "Deserto",
            description = "Ceu limpo a perder de vista",
            photoUrl = "/api/photos/x.jpg",
            latitude = -25.0,
            longitude = 120.0
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.longitude").value(120.0))
    }

    @Test
    fun `rejects a longitude outside the valid range`() {
        val user = persistUser(email = "offmap@skydex.com")

        val payload = CreateWeatherEventRequest(
            title = "Impossible",
            description = "Off the planet",
            photoUrl = "/api/photos/x.jpg",
            latitude = 0.0,
            longitude = 200.0
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("longitude: must be between -180 and 180"))
    }

    @Test
    fun `rejects a photo url pointing at a foreign host`() {
        val user = persistUser(email = "pixel@skydex.com")

        // This app shows one user's captures to their friends, so photoUrl is not merely the
        // author's own business: every friend who opens the feed fetches whatever it names. An
        // unconstrained string is a tracking pixel any user can plant in everyone else's client.
        val payload = CreateWeatherEventRequest(
            title = "Parece uma foto",
            description = "Mas nao esta no nosso servidor",
            photoUrl = "http://attacker.example/pixel.jpg",
            latitude = 0.0,
            longitude = 0.0
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("photoUrl: Photo URL must be a path returned by POST /api/photos"))
    }

    @Test
    fun `rejects a photo url that escapes the photos path`() {
        val user = persistUser(email = "traversal@skydex.com")

        // Same origin, wrong endpoint. Excluding `/` from the filename is what stops this, and it
        // is the half that a "must start with /api/photos" check would miss.
        val payload = CreateWeatherEventRequest(
            title = "Ainda parece",
            description = "Mas sobe um nivel",
            photoUrl = "/api/photos/../../api/users/me",
            latitude = 0.0,
            longitude = 0.0
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `ignores a capture time supplied by the client`() {
        val user = persistUser(email = "liar@skydex.com")

        // Hand-built JSON, because the DTO has no `capturedAt` field to set. A gamified app pays
        // XP and rare badges for matching real weather, so a client that could name the hour
        // could look up yesterday's thunderstorm and farm "Pé de Raio" without seeing a storm.
        // Jackson ignores unknown properties by default; this pins that the value is discarded
        // rather than honoured.
        val backdated = """
            {"title":"Ontem","description":"Faz de conta","photoUrl":"/api/photos/x.jpg",
             "latitude":0.0,"longitude":0.0,"capturedAt":"2020-01-01T00:00:00Z"}
        """.trimIndent()

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(backdated)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.capturedAt").value(org.hamcrest.Matchers.not("2020-01-01T00:00:00Z")))
    }
}
