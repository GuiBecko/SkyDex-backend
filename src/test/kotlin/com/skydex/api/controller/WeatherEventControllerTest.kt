package com.skydex.api.controller

import com.skydex.api.domain.ValidationStatus
import com.skydex.api.dto.CreateWeatherEventRequest
import com.skydex.api.dto.HourlyData
import com.skydex.api.dto.OpenMeteoResponse
import com.skydex.api.models.User
import com.skydex.api.services.OpenMeteoClient
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistEvent
import com.skydex.api.support.persistUploadedPhoto
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.test.mock.mockito.MockBean
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
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WeatherEventControllerTest : IntegrationTestBase() {

    @MockBean
    private lateinit var openMeteoClient: OpenMeteoClient

    private lateinit var testUser: User
    private lateinit var authHeader: String

    // Before each test, create a user to associate with the events and a bearer token for it
    @BeforeEach
    fun setUpFixtures() {
        testUser = persistUser(name = "Test Pilot", email = "pilot@skydex.com")
        authHeader = authHeaderFor(testUser)
    }

    /**
     * The `/api/photos/<name>` path of a photo [owner] just uploaded, recorded exactly as
     * `POST /api/photos` would have. Since Task 12b, `POST /api/events` refuses any photoUrl no
     * `UploadedPhoto` row of the caller's backs, so every create-path test needs one of these.
     *
     * [uploadedAt] defaults to now and the age fixtures below are all offsets from `Instant.now()`:
     * a literal date here would silently drift past MAX_AGE and turn these tests into a clock.
     */
    private fun freshPhotoFor(owner: User, uploadedAt: Instant = Instant.now()): String =
        "/api/photos/" + persistUploadedPhoto(owner, uploadedAt = uploadedAt).filename

    @Test
    fun `registers a new event and returns 201 with a generated id`() {
        val request = CreateWeatherEventRequest(
            title = "Aurora Borealis",
            description = "Bright green lights in the night sky.",
            photoUrl = freshPhotoFor(testUser),
            latitude = -23.55,
            longitude = -46.63,
            phenomenon = "RAIN"
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
            longitude = -46.63,
            phenomenon = "RAIN"
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Tornado Confirmed"))
            // url1, not url2: since Task 12b photoUrl is frozen after creation, so the request's
            // url2 is accepted and ignored. The dedicated test below owns that property.
            .andExpect(jsonPath("$.photoUrl").value("http://localhost:8080/api/photos/url1.jpg"))
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
            longitude = -46.63,
            phenomenon = "RAIN"
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

        // The photoUrl this sends is ignored (frozen since Task 12b); what is under test here is
        // that the update RESPONSE composes the stored relative path into an absolute one, the
        // same as the other three response sites.
        val request = CreateWeatherEventRequest(
            title = "New",
            description = "New description",
            photoUrl = "/api/photos/new.jpg",
            latitude = -23.55,
            longitude = -46.63,
            phenomenon = "RAIN"
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photoUrl").value("http://localhost:8080/api/photos/old.jpg"))

        assertEquals("/api/photos/old.jpg", weatherEventRepository.findById(event.id!!).orElseThrow().photoUrl)
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
            longitude = -46.63,
            phenomenon = "RAIN"
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
            photoUrl = freshPhotoFor(user),
            latitude = -30.0346,
            longitude = -51.2177,
            phenomenon = "RAIN"
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
            longitude = 0.0,
            phenomenon = "RAIN"
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
            longitude = 139.6503,
            phenomenon = "RAIN"
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
            photoUrl = freshPhotoFor(user),
            latitude = -25.0,
            longitude = 120.0,
            phenomenon = "RAIN"
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
            longitude = 200.0,
            phenomenon = "RAIN"
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
            longitude = 0.0,
            phenomenon = "RAIN"
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
            longitude = 0.0,
            phenomenon = "RAIN"
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
        // The photoUrl is interpolated rather than literal because this request is hand-built:
        // since Task 12b it must name a real, fresh, unspent upload of this caller's, and no
        // compiler will point here when that stops being true.
        val backdated = """
            {"title":"Ontem","description":"Faz de conta","photoUrl":"${freshPhotoFor(user)}",
             "latitude":0.0,"longitude":0.0,"capturedAt":"2020-01-01T00:00:00Z","phenomenon":"RAIN"}
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

    /**
     * Open-Meteo's label for the hour the server is in right now, e.g. "2026-08-07T14:00".
     *
     * The capture time is stamped server-side with `Instant.now()` (Task 6), so a forecast slot
     * hard-coded to a fixed date would drift out of `MAX_SKEW` and every one of these tests would
     * start failing on a wall clock the author never ran. Deriving the slot from the same clock
     * the server reads keeps them honest instead of merely green.
     */
    private fun currentSlotLabel(): String =
        LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.HOURS), ZoneOffset.UTC).toString()

    @Test
    fun `confirms a capture whose claim matches the observed weather and awards xp`() {
        val user = persistUser(email = "hunter@skydex.com")

        `when`(openMeteoClient.fetchHourlyForecast(-30.0346, -51.2177)).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0346,
                longitude = -51.2177,
                hourly = HourlyData(
                    time = listOf(currentSlotLabel()),
                    temperatureCelsius = listOf(19.0),
                    weatherCode = listOf(95)
                )
            )
        )

        val payload = CreateWeatherEventRequest(
            title = "Tempestade",
            description = "Raios sobre o bairro",
            photoUrl = freshPhotoFor(user),
            latitude = -30.0346,
            longitude = -51.2177,
            phenomenon = "THUNDERSTORM"
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.phenomenon").value("THUNDERSTORM"))
            .andExpect(jsonPath("$.validationStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(60))
    }

    @Test
    fun `saves the capture but awards no xp when the claim is contradicted`() {
        val user = persistUser(email = "optimist@skydex.com")

        `when`(openMeteoClient.fetchHourlyForecast(-30.0346, -51.2177)).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0346,
                longitude = -51.2177,
                hourly = HourlyData(
                    time = listOf(currentSlotLabel()),
                    temperatureCelsius = listOf(28.0),
                    weatherCode = listOf(0)
                )
            )
        )

        val payload = CreateWeatherEventRequest(
            title = "Granizo (eu juro)",
            description = "Pedras do tamanho de bolas de golfe",
            photoUrl = freshPhotoFor(user),
            latitude = -30.0346,
            longitude = -51.2177,
            phenomenon = "HAILSTORM"
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(0))
    }

    @Test
    fun `rejects a phenomenon that is not in the catalog without consuming the photo`() {
        val user = persistUser(email = "inventor@skydex.com")
        // A real, valid, unspent photo of this user's, so the rejection can only come from the
        // phenomenon. It also makes the rule explicit rather than incidental: a request thrown out
        // for something unrelated to the photo must give the photo back, which today holds because
        // the phenomenon parse sits above the provenance check in `create` and nothing else pinned
        // that ordering.
        val photo = persistUploadedPhoto(user)

        val payload = CreateWeatherEventRequest(
            title = "Chuva de sapos",
            description = "Aconteceu mesmo",
            photoUrl = "/api/photos/${photo.filename}",
            latitude = -30.0346,
            longitude = -51.2177,
            phenomenon = "FROG_RAIN"
        )

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Unknown phenomenon: FROG_RAIN"))

        assertNull(
            uploadedPhotoRepository.findByFilename(photo.filename)!!.consumedAt,
            "a request rejected for its phenomenon burnt the caller's photo"
        )
    }

    @Test
    fun `editing a capture does not re-roll its validation or xp`() {
        val user = persistUser(email = "rerooler@skydex.com")
        // UNCONFIRMED / 0 XP passed EXPLICITLY. `persistEvent` defaults to CONFIRMED with RAIN's
        // 10 XP (Step 11), which would both contradict the assertions below and destroy the
        // point of the test: starting from CONFIRMED, a handler that re-validated would leave it
        // CONFIRMED and the test would pass while proving nothing.
        val event = persistEvent(
            owner = user,
            latitude = -30.0346,
            longitude = -51.2177,
            validationStatus = ValidationStatus.UNCONFIRMED,
            xpAwarded = 0
        )

        // The capture is stored UNCONFIRMED with 0 XP. Make the forecast agree
        // now, so a handler that re-validated on edit would flip it to CONFIRMED and pay out.
        `when`(openMeteoClient.fetchHourlyForecast(-30.0346, -51.2177)).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0346,
                longitude = -51.2177,
                hourly = HourlyData(
                    time = listOf(currentSlotLabel()),
                    temperatureCelsius = listOf(19.0),
                    weatherCode = listOf(95)
                )
            )
        )

        val payload = CreateWeatherEventRequest(
            title = "Titulo novo",
            description = "So o texto mudou",
            photoUrl = "/api/photos/x.jpg",
            latitude = -30.0346,
            longitude = -51.2177,
            phenomenon = "THUNDERSTORM"
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Titulo novo"))
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(0))
    }

    // --- Task 12b: a capture must cite a photo the caller actually took -------------------------

    /** Builds the JSON for a create request citing [photoUrl]; the rest is deliberately boring. */
    private fun captureCiting(photoUrl: String): String = objectMapper.writeValueAsString(
        CreateWeatherEventRequest(
            title = "Chuva",
            description = "Pingos grossos",
            photoUrl = photoUrl,
            latitude = -30.0346,
            longitude = -51.2177,
            phenomenon = "RAIN"
        )
    )

    @Test
    fun `accepts a capture citing a photo the caller just uploaded and marks it spent`() {
        val photo = persistUploadedPhoto(testUser)

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureCiting("/api/photos/${photo.filename}"))
        )
            .andExpect(status().isCreated)

        // A photo is single-use, and this stamp is the whole mechanism: it is what makes the
        // second citation of the same filename fail below.
        val spent = uploadedPhotoRepository.findByFilename(photo.filename)
        assertNotNull(spent, "the upload row vanished")
        assertNotNull(spent!!.consumedAt, "the capture did not mark the photo it cited as spent")
    }

    /**
     * The core of the task: the photos directory is one shared namespace, so without an ownership
     * check any authenticated user could scrape a friend's photo path out of a feed response and
     * submit it as their own capture.
     */
    @Test
    fun `refuses a capture citing another user's photo`() {
        val stranger = persistUser(email = "stranger@skydex.com")
        val theirPhoto = persistUploadedPhoto(stranger)

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureCiting("/api/photos/${theirPhoto.filename}"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Photo is not available for this capture"))

        // Rejected before validation, so a bad photo costs no upstream call at all.
        verify(openMeteoClient, never()).fetchHourlyForecast(anyDouble(), anyDouble())
        // And it did not consume someone else's photo on the way out.
        assertNull(uploadedPhotoRepository.findByFilename(theirPhoto.filename)!!.consumedAt)
        assertEquals(0L, weatherEventRepository.count())
    }

    /**
     * Deliberately the SAME message as the test above, and the property it protects is OWNERSHIP
     * PRIVACY rather than the secrecy of the filename. Which files exist is already public: the
     * GET side of the photos endpoint is permitAll, so anyone can probe a path unauthenticated and
     * read existence off 200 versus 404. What splitting the message would newly disclose is
     * *whose* a photo is — an authenticated user could sweep paths scraped from a feed and sort
     * them into "nobody's" and "somebody's". One message keeps that unanswerable.
     */
    @Test
    fun `refuses a capture citing a filename no upload backs, with the same message`() {
        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureCiting("/api/photos/${UUID.randomUUID()}.jpg"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Photo is not available for this capture"))

        verify(openMeteoClient, never()).fetchHourlyForecast(anyDouble(), anyDouble())
        assertEquals(0L, weatherEventRepository.count())
    }

    /**
     * Already-spent and expired are the caller's OWN photos, so a specific message leaks nothing
     * and tells them something actionable.
     */
    @Test
    fun `refuses a capture citing a photo an earlier capture already spent`() {
        val photo = persistUploadedPhoto(testUser)
        val body = captureCiting("/api/photos/${photo.filename}")

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isCreated)

        // Same photo, second capture. Otherwise one lucky shot could be farmed into a whole album.
        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("This photo has already been used for a capture"))

        // Exactly one upstream call in the whole test, i.e. the first POST's. The rejection is
        // decided by verify(), above the Open-Meteo call, so the second POST spent nothing.
        verify(openMeteoClient, times(1)).fetchHourlyForecast(anyDouble(), anyDouble())
        assertEquals(1L, weatherEventRepository.count())
    }

    @Test
    fun `refuses a capture citing a photo uploaded thirty-one minutes ago`() {
        // MAX_AGE is 30 minutes, and this offset is taken from Instant.now() rather than written
        // as a date: a literal would drift past the limit on its own and pass for the wrong reason.
        val stale = persistUploadedPhoto(testUser, uploadedAt = Instant.now().minus(31, ChronoUnit.MINUTES))

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureCiting("/api/photos/${stale.filename}"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Photo has expired; take a new one"))

        verify(openMeteoClient, never()).fetchHourlyForecast(anyDouble(), anyDouble())
        assertEquals(0L, weatherEventRepository.count())
    }

    /** The freshness window is generous on purpose — a slow upload must not cost the capture. */
    @Test
    fun `accepts a capture citing a photo uploaded twenty-nine minutes ago`() {
        val nearlyStale = persistUploadedPhoto(testUser, uploadedAt = Instant.now().minus(29, ChronoUnit.MINUTES))

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(captureCiting("/api/photos/${nearlyStale.filename}"))
        )
            .andExpect(status().isCreated)
    }

    /**
     * photoUrl joins capturedAt, the coordinates and the score as frozen after creation. The
     * update path never calls `claim`, so an editable photoUrl would let a capture be scored
     * against a real photo and then have the evidence swapped for anything at all afterwards —
     * including a photo that is not the caller's.
     */
    @Test
    fun `ignores a photo url supplied on update`() {
        val event = persistEvent(owner = testUser, photoUrl = "/api/photos/original.jpg")
        val substitute = persistUploadedPhoto(testUser)

        val payload = CreateWeatherEventRequest(
            title = "Editado",
            description = "So o texto mudou",
            photoUrl = "/api/photos/${substitute.filename}",
            latitude = -23.55,
            longitude = -46.63,
            phenomenon = "RAIN"
        )

        mockMvc.perform(
            put("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Editado"))
            .andExpect(jsonPath("$.photoUrl").value("http://localhost:8080/api/photos/original.jpg"))

        assertEquals("/api/photos/original.jpg", weatherEventRepository.findById(event.id!!).orElseThrow().photoUrl)
        // Accepted and ignored, not claimed: update must not spend the photo it was handed either.
        assertNull(uploadedPhotoRepository.findByFilename(substitute.filename)!!.consumedAt)
    }

    // --- Task 12c: a capture must be somewhere the caller could plausibly be --------------------

    private val portoAlegre = Pair(-30.0346, -51.2177)

    /** Roughly 18,500 km from [portoAlegre]: unreachable in anything under about twenty hours. */
    private val tokyo = Pair(35.6762, 139.6503)

    /** About 10 km north of [portoAlegre] — 0.09 degrees of latitude. */
    private val nearPortoAlegre = Pair(-29.9446, -51.2177)

    /**
     * Puts [user]'s movement trail at ([latitude], [longitude]) as of [at], the way a previous
     * capture would have. Written straight to the row because the alternative — making a real
     * capture first — cannot control the elapsed time: `capturedAt` is server-stamped, so two
     * captures through the API are always milliseconds apart, and "an hour later" is
     * unrepresentable that way.
     */
    private fun recordTrail(user: User, latitude: Double, longitude: Double, at: Instant) {
        user.lastCaptureLatitude = latitude
        user.lastCaptureLongitude = longitude
        user.lastCaptureAt = at
        userRepository.save(user)
    }

    /** A THUNDERSTORM-agreeing forecast at ([latitude], [longitude]) for the current hour, so that
     *  a capture there can only fail to confirm on position. */
    private fun thunderstormAt(latitude: Double, longitude: Double) {
        `when`(openMeteoClient.fetchHourlyForecast(latitude, longitude)).thenReturn(
            OpenMeteoResponse(
                latitude = latitude,
                longitude = longitude,
                hourly = HourlyData(
                    time = listOf(currentSlotLabel()),
                    temperatureCelsius = listOf(19.0),
                    weatherCode = listOf(95)
                )
            )
        )
    }

    private fun thunderstormCapture(
        photoUrl: String,
        at: Pair<Double, Double>,
        locationIsMock: Boolean = false
    ): String = objectMapper.writeValueAsString(
        CreateWeatherEventRequest(
            title = "Tempestade",
            description = "Raios sobre o bairro",
            photoUrl = photoUrl,
            latitude = at.first,
            longitude = at.second,
            phenomenon = "THUNDERSTORM",
            locationIsMock = locationIsMock
        )
    )

    private fun postCapture(user: User, body: String) = mockMvc.perform(
        post("/api/events")
            .header("Authorization", authHeaderFor(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    @Test
    fun `confirms a capture ten kilometres and an hour on from the recorded position`() {
        val user = persistUser(email = "cyclist@skydex.com")
        recordTrail(user, portoAlegre.first, portoAlegre.second, Instant.now().minusSeconds(3600))
        thunderstormAt(nearPortoAlegre.first, nearPortoAlegre.second)

        postCapture(user, thunderstormCapture(freshPhotoFor(user), nearPortoAlegre))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(60))
    }

    @Test
    fun `does not confirm a capture another continent away minutes after the last one`() {
        val user = persistUser(email = "teleporter@skydex.com")
        recordTrail(user, portoAlegre.first, portoAlegre.second, Instant.now().minusSeconds(300))
        // The weather in Tokyo genuinely matches the claim, so only the journey can explain this.
        thunderstormAt(tokyo.first, tokyo.second)

        postCapture(user, thunderstormCapture(freshPhotoFor(user), tokyo))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(0))

        // The trail follows an UNCONFIRMED capture too. If it only followed confirmed ones, this
        // rejected hop would leave the trail in Porto Alegre and the NEXT capture in Tokyo would
        // be measured from there and rejected as well — but a cheater could equally park the trail
        // somewhere convenient with a capture they never intended to have confirmed. The trail
        // records where the client CLAIMED to be, which is the sequence being tested for coherence.
        val moved = userRepository.findById(user.id!!).orElseThrow()
        assertEquals(tokyo.first, moved.lastCaptureLatitude)
        assertEquals(tokyo.second, moved.lastCaptureLongitude)
    }

    @Test
    fun `confirms a first capture, which has no recorded position to contradict`() {
        val user = persistUser(email = "newcomer@skydex.com")
        assertNull(user.lastCaptureAt, "a brand new user must start with no trail")
        thunderstormAt(tokyo.first, tokyo.second)

        postCapture(user, thunderstormCapture(freshPhotoFor(user), tokyo))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(60))

        val trailed = userRepository.findById(user.id!!).orElseThrow()
        assertEquals(tokyo.first, trailed.lastCaptureLatitude)
        assertEquals(tokyo.second, trailed.lastCaptureLongitude)
        assertNotNull(trailed.lastCaptureAt, "the first capture did not start the trail")
    }

    @Test
    fun `does not confirm a capture the client reports as mock-located`() {
        val user = persistUser(email = "spoofer@skydex.com")
        thunderstormAt(portoAlegre.first, portoAlegre.second)

        postCapture(
            user,
            thunderstormCapture(freshPhotoFor(user), portoAlegre, locationIsMock = true)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(0))

        // Nothing was scored, so nothing was asked upstream.
        verify(openMeteoClient, never()).fetchHourlyForecast(anyDouble(), anyDouble())
    }

    /**
     * `locationIsMock` is defaulted, and this is what that default is for: the Android client in
     * users' hands does not send the field until Task 14, so a required one would 400 every capture
     * the day this shipped. Hand-built JSON, because a `CreateWeatherEventRequest` serialised by
     * Jackson always carries the field and so could never reproduce an older client's payload.
     *
     * Confirming rather than merely accepting is the second half: it pins that an absent flag reads
     * as `false`, not as `true`, which would silently deny XP to every existing user instead.
     */
    @Test
    fun `accepts a capture from a client that does not send the mock-location flag`() {
        val user = persistUser(email = "oldclient@skydex.com")
        thunderstormAt(portoAlegre.first, portoAlegre.second)

        val legacyPayload = """
            {"title":"Tempestade","description":"Raios","photoUrl":"${freshPhotoFor(user)}",
             "latitude":${portoAlegre.first},"longitude":${portoAlegre.second},
             "phenomenon":"THUNDERSTORM"}
        """.trimIndent()

        postCapture(user, legacyPayload)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("CONFIRMED"))
    }

    /**
     * The trail is read once, unsynchronised, at the start of the request, and the Open-Meteo call
     * sits between that read and the commit. So a capture can be judged reachable against a trail
     * that has already moved by the time it is written — this reproduces exactly that interleaving,
     * deterministically, by moving the trail from inside the forecast stub.
     *
     * `CaptureCommitService` re-reads the row under a lock and downgrades, which is what makes the
     * outcome UNCONFIRMED rather than a confirmed teleport.
     */
    @Test
    fun `downgrades a capture whose trail moved while the forecast was being fetched`() {
        val user = persistUser(email = "interleaved@skydex.com")

        `when`(openMeteoClient.fetchHourlyForecast(tokyo.first, tokyo.second)).thenAnswer {
            // Stands in for a concurrent capture of this user's that committed while we were
            // waiting on the network. It leaves the trail in Porto Alegre, as of now.
            recordTrail(user, portoAlegre.first, portoAlegre.second, Instant.now())
            OpenMeteoResponse(
                latitude = tokyo.first,
                longitude = tokyo.second,
                hourly = HourlyData(
                    time = listOf(currentSlotLabel()),
                    temperatureCelsius = listOf(19.0),
                    weatherCode = listOf(95)
                )
            )
        }

        postCapture(user, thunderstormCapture(freshPhotoFor(user), tokyo))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(0))
    }

    /**
     * The attack the re-check exists to stop, run for real rather than simulated.
     *
     * A day of not capturing makes the reachable radius the whole planet, so every one of these
     * passes the up-front check — they are all measured against the same stale budget. Fired
     * concurrently they would ALL confirm, turning `MAX_SPEED_KMH`'s "one intercontinental hop a
     * day" into "unlimited hops, in one burst, once a day", which is the collect-several-in-an-
     * afternoon exploit the whole task is about. Serialised on the user row, the first one to
     * commit spends the budget and the rest are downgraded.
     *
     * Asserted as "exactly one", not "at most one": that also catches a lock so eager it refuses
     * every capture in the burst.
     */
    @Test
    fun `confirms only one of a burst of simultaneous captures scattered across the globe`() {
        val user = persistUser(email = "burst@skydex.com")
        recordTrail(user, portoAlegre.first, portoAlegre.second, Instant.now().minusSeconds(86_400))

        // Four corners of the world, all with weather that agrees, each with its own fresh photo.
        val destinations = listOf(tokyo, Pair(64.1466, -21.9426), Pair(-33.8688, 151.2093), Pair(51.5072, -0.1276))
        destinations.forEach { thunderstormAt(it.first, it.second) }
        val payloads = destinations.map { thunderstormCapture(freshPhotoFor(user), it) }

        val pool = Executors.newFixedThreadPool(destinations.size)
        val startLine = CountDownLatch(1)
        val statuses = try {
            val inFlight = payloads.map { payload ->
                pool.submit<String> {
                    startLine.await()
                    objectMapper.readTree(
                        postCapture(user, payload).andReturn().response.contentAsString
                    ).get("validationStatus").asText()
                }
            }
            startLine.countDown()
            inFlight.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            1,
            statuses.count { it == "CONFIRMED" },
            "expected exactly one of a simultaneous burst to confirm, got $statuses"
        )
    }

    /**
     * The test that pins the design, and the reason the trail is a column on `users` rather than a
     * query over capture rows.
     *
     * `DELETE /api/events/{id}` is unrestricted for the owner. Derive "where was this user last"
     * from their captures — the obvious `findFirstByUserIdOrderByCapturedAtDesc` — and the cheater
     * erases their own history: capture in Porto Alegre, delete it, and the Tokyo capture minutes
     * later has nothing left to be implausible against. A trail on `users` is never deleted, so it
     * still remembers.
     */
    @Test
    fun `deleting the previous capture does not clear the movement trail`() {
        val user = persistUser(email = "eraser@skydex.com")
        thunderstormAt(portoAlegre.first, portoAlegre.second)
        thunderstormAt(tokyo.first, tokyo.second)

        val firstId = objectMapper.readTree(
            postCapture(user, thunderstormCapture(freshPhotoFor(user), portoAlegre))
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString
        ).get("id").asText()

        mockMvc.perform(
            delete("/api/events/{id}", firstId).header("Authorization", authHeaderFor(user))
        ).andExpect(status().isNoContent)

        assertTrue(
            weatherEventRepository.findById(UUID.fromString(firstId)).isEmpty,
            "the capture the trail came from is supposed to be gone"
        )

        postCapture(user, thunderstormCapture(freshPhotoFor(user), tokyo))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(0))
    }
}
