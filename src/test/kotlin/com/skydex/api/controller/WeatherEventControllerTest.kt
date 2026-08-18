package com.skydex.api.controller

import com.skydex.api.domain.UnconfirmedReason
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.dto.CreateWeatherEventRequest
import com.skydex.api.dto.HourlyData
import com.skydex.api.dto.OpenMeteoResponse
import com.skydex.api.dto.VisionAnalysis
import com.skydex.api.models.User
import com.skydex.api.services.BadgeService
import com.skydex.api.services.OpenMeteoClient
import com.skydex.api.services.VisionClient
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
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
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

    /**
     * A spy, not a mock: badges are awarded for real in every test here except the one that makes
     * this throw on purpose. Spring resets it between test methods, so that stubbing does not leak.
     */
    @SpyBean
    private lateinit var badgeServiceSpy: BadgeService

    /**
     * Only one test in this class (`stores a photo url relative but returns it absolute`) drives
     * `POST /api/photos` for real rather than seeding a row with `persistUploadedPhoto`; everything
     * else in `PhotoController.upload` since Task 3 now depends on this answering, so it is stubbed
     * for every test here rather than only where it is exercised.
     */
    @MockBean
    private lateinit var vision: VisionClient

    private lateinit var testUser: User
    private lateinit var authHeader: String

    // Before each test, create a user to associate with the events and a bearer token for it
    @BeforeEach
    fun setUpFixtures() {
        testUser = persistUser(name = "Test Pilot", email = "pilot@skydex.com")
        authHeader = authHeaderFor(testUser)
        // The full six-group map the brief specified, STORM-leaning at 0.80 with the remainder split
        // evenly across the other five groups (the same shape `PhotoAuthenticityServiceTest`'s
        // `confident` helper builds). A single-key stub would only pass because that one key
        // happened to sit in STORM's reconcilable set against the `code = 95` forecast every
        // Task 6 test here stubs — tightening the contradiction matrix would then fail unrelated
        // tests for a reason that has nothing to do with what they assert.
        `when`(vision.analyze(any(), any())).thenReturn(
            VisionAnalysis(
                outdoorScore = 0.94,
                phenomenonScores = mapOf(
                    "CLEAR" to 0.04, "CLOUDY" to 0.04, "FOG" to 0.04,
                    "RAIN" to 0.04, "SNOW" to 0.04, "STORM" to 0.80
                ),
                model = "clip-vit-b-32-zeroshot-v1"
            )
        )
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

    /**
     * `ArgumentMatchers.any()` returns null, and Kotlin plants a non-null assertion at the call
     * site of any function taking a non-null reference — so the bare matcher throws
     * "any(...) must not be null" before Mockito can record it. Routing it through an unbounded
     * type parameter erases the nullability and lets the matcher register normally.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyRef(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return null as T
    }

    @Test
    fun `registers a new event and returns 201 with a generated id`() {
        // Open-Meteo is now a mandatory dependency of every capture (Task 5), not merely consulted
        // when position and mock-location pass first as it used to be. Before Task 6 this test
        // passed with no stub at all, because an unstubbed Open-Meteo returned null and the old
        // service treated that as a soft UNCONFIRMED rather than a 503. It is not the phenomenon
        // that this test cares about, so the stub is unremarkable — but it has to be there.
        thunderstormAt(-23.55, -46.63)
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
        // See the comment on `registers a new event...` above: Open-Meteo is now mandatory, so an
        // unstubbed call would 503 rather than degrade to UNCONFIRMED as it used to.
        thunderstormAt(-23.55, -46.63)
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
        // (`GET /api/events/{id}` was a third such endpoint until it was removed for exposing
        // strangers' captures; `/mine` and the create response above cover the same composition.)
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

    /**
     * `GET /api/events/{id}` is gone, on purpose, and this is what keeps it gone.
     *
     * It used to serve any capture to any authenticated caller — a stranger's exact coordinates,
     * capture time and photo URL — which is the opposite of the friends-only model Task 16 built
     * for the feed. It was removed rather than scoped, because nothing consumed it.
     *
     * The expected status is **405, not 404**, and the distinction is the assertion's whole value.
     * `PUT` and `DELETE` still map that path, so Spring matches the pattern, finds no `GET` among
     * its methods and reports method-not-allowed. A 404 here would mean the path stopped matching
     * altogether; a 200 would mean someone put the handler back. This test therefore also pins
     * that `GlobalExceptionHandler`'s catch-all does not swallow Spring's own dispatch exceptions
     * and turn a routing decision into a 500.
     *
     * The event is real and owned by the caller, so nothing but the absent route can produce the
     * refusal — an unknown id would have been refused by a live handler too, and would have
     * passed against the code this test exists to reject.
     */
    @Test
    fun `does not expose a capture by id`() {
        val event = persistEvent(owner = testUser, title = "Aurora Borealis", description = "lights")

        val body = mockMvc.perform(
            get("/api/events/{id}", event.id!!)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isMethodNotAllowed)
            .andReturn().response.contentAsString

        assertFalse(body.contains("Aurora Borealis"), "the removed endpoint still served a capture")
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
        // Open-Meteo is now mandatory; see the comment on `registers a new event...` above.
        thunderstormAt(-30.0346, -51.2177)
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
        // Open-Meteo is now mandatory; see the comment on `registers a new event...` above.
        thunderstormAt(-25.0, 120.0)

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
        // Open-Meteo is now mandatory; see the comment on `registers a new event...` above.
        thunderstormAt(0.0, 0.0)

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

    /**
     * Formerly `saves the capture but awards no xp when the claim is contradicted`: it posted a
     * "HAILSTORM" claim against a stubbed forecast of code 0 and expected UNCONFIRMED with 0 XP,
     * because the old service compared the user's claim against Open-Meteo's record and scored a
     * mismatch as unconfirmed. That comparison is gone — there is no claim left to contradict, only
     * a phenomenon Open-Meteo reports and a photo that may or may not be consistent with it.
     * `freshPhotoFor` seeds an `UploadedPhoto` row with no cached vision scores, so stage 2 has
     * nothing to compare either (`PhotoAuthenticityService.contradicts` reads `null` as no opinion).
     * With nothing left to disagree with the weather, this capture is CONFIRMED — for CLEAR_SKY,
     * what code 0 actually maps to, not the HAILSTORM the client still sent. That is the feature:
     * the claim is well and truly ignored, all the way down to the reward it earns.
     */
    @Test
    fun `ignores a contradicted claim and confirms the phenomenon open-meteo actually reported`() {
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
            .andExpect(jsonPath("$.phenomenon").value("CLEAR_SKY"))
            .andExpect(jsonPath("$.validationStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(10))
    }

    // `rejects a phenomenon that is not in the catalog without consuming the photo` was deleted
    // here (Task 6). It asserted a 400 with "Unknown phenomenon: FROG_RAIN" for a phenomenon the
    // catalog does not recognise. That case no longer exists: `phenomenon` is accepted and ignored
    // on every request now (`CreateWeatherEventRequest.phenomenon`'s KDoc explains why an
    // already-installed client must never be 400'd for it), so there is nothing left to reject and
    // no scenario for this test to exercise.

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
        // Open-Meteo is now mandatory; see the comment on `registers a new event...` above.
        thunderstormAt(-30.0346, -51.2177)

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
        // Open-Meteo is now mandatory for the first, successful POST below; see the comment on
        // `registers a new event...` above. The second POST never reaches it (asserted below).
        thunderstormAt(-30.0346, -51.2177)
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
        // Open-Meteo is now mandatory; see the comment on `registers a new event...` above.
        thunderstormAt(-30.0346, -51.2177)

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
            .andExpect(jsonPath("$.unconfirmedReason").value("MOCK_LOCATION"))
            .andExpect(jsonPath("$.xpAwarded").value(0))

        // Formerly asserted `never()`: the old service checked `locationIsMock` before any network
        // call, so a mocked location cost no Open-Meteo request. Task 6 inverts that ordering on
        // purpose (see `CaptureValidationService`'s KDoc, "The Open-Meteo call is no longer
        // optional") — even a mock-located capture needs a phenomenon to store, since
        // `weather_events.phenomenon` is NOT NULL, so the call now always happens.
        verify(openMeteoClient, times(1)).fetchHourlyForecast(anyDouble(), anyDouble())
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
     *
     * The reason is asserted too, not just status and xp: `CaptureCommitService.commit` writes
     * `IMPLAUSIBLE_TRAVEL` for this branch, and nothing else in the suite pinned that assignment —
     * deleting it left the suite green.
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
            .andExpect(jsonPath("$.unconfirmedReason").value("IMPLAUSIBLE_TRAVEL"))
            .andExpect(jsonPath("$.xpAwarded").value(0))
    }

    /**
     * The other half of the same line: `CaptureCommitService.commit` must NOT relabel a capture
     * already marked `MOCK_LOCATION` as `IMPLAUSIBLE_TRAVEL` just because the locked re-check also
     * disagrees. `MOCK_LOCATION` is the more specific, more actionable diagnosis — a mocked position
     * failing a travel check is a consequence of the mocking, not an independent finding — so the
     * commit-time overwrite must leave it alone.
     *
     * Same interleaving as the test above (the trail moves from inside the forecast stub), but this
     * capture also reports `locationIsMock = true`, so `CaptureValidationService` provisionally
     * marks it `MOCK_LOCATION` before the network call. The locked re-check then also finds the
     * (mocked) claimed position unreachable from where the trail moved to. An unconditional
     * overwrite would rewrite the reason to `IMPLAUSIBLE_TRAVEL`; the guarded one keeps `MOCK_LOCATION`.
     */
    @Test
    fun `does not relabel a mock-located capture as implausible travel after the locked re-check`() {
        val user = persistUser(email = "mocked-interleaved@skydex.com")

        `when`(openMeteoClient.fetchHourlyForecast(tokyo.first, tokyo.second)).thenAnswer {
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

        postCapture(
            user,
            thunderstormCapture(freshPhotoFor(user), tokyo, locationIsMock = true)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.unconfirmedReason").value("MOCK_LOCATION"))
            .andExpect(jsonPath("$.xpAwarded").value(0))

        val stored = weatherEventRepository.findAll().first { it.userId == user.id }
        assertEquals(UnconfirmedReason.MOCK_LOCATION, stored.unconfirmedReason)
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

    /**
     * A badge failure must not fail a capture that has already been committed.
     *
     * `badges.syncFor` runs after `captureCommit.commit` has returned, deliberately outside its
     * transaction. That placement is correct and stays — but it means anything `syncFor` throws
     * that is not the `DataIntegrityViolationException` it already recovers from lands after the
     * capture row is inserted and after the photo has been stamped `consumed_at`. The caller then
     * gets a 500 for a capture that exists, whose photo is spent, and which no retry can recreate:
     * every subsequent attempt cites the same spent photo and is refused with
     * "This photo has already been used for a capture", forever.
     *
     * So this asserts the whole invariant, not just the status code: 201, the row is really there,
     * and the photo really was consumed. A handler that answered 201 by skipping the commit would
     * satisfy the first assertion alone.
     *
     * Badges are the right thing to sacrifice here. `ProfileService.forUser` calls `syncFor` on
     * every profile read, so anything missed now is awarded the next time the user opens Profile.
     */
    @Test
    fun `a badge failure after the capture is committed does not fail the capture`() {
        val user = persistUser(email = "badge-explodes@skydex.com")
        thunderstormAt(portoAlegre.first, portoAlegre.second)
        val photoUrl = freshPhotoFor(user)

        doThrow(IllegalStateException("badge sync exploded"))
            .`when`(badgeServiceSpy).syncFor(anyRef())

        val body = postCapture(user, thunderstormCapture(photoUrl, portoAlegre))
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        val id = UUID.fromString(objectMapper.readTree(body).get("id").asText())
        assertTrue(
            weatherEventRepository.findById(id).isPresent,
            "the capture was reported created but is not in the database"
        )
        assertNotNull(
            uploadedPhotoRepository.findByFilename(photoUrl.substringAfterLast('/'))?.consumedAt,
            "the photo should have been spent by the commit that already succeeded"
        )
    }

    // --- Task 6: Open-Meteo decides the phenomenon, and a 503 must cost nothing ------------------

    @Test
    fun `takes the phenomenon from open-meteo and ignores what the client sent`() {
        // The shipped Android app still sends `phenomenon`. It must be accepted and ignored, not
        // rejected: making it a 400 would break every capture from an app already installed.
        stubForecast(code = 95)   // thunderstorm
        val user = persistUser(email = "ignored@skydex.com")
        val photoUrl = uploadPhotoFor(user)

        val body = mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"t","description":"d","photoUrl":"$photoUrl",
                     "latitude":-30.0,"longitude":-51.0,
                     "phenomenon":"CLEAR_SKY","locationIsMock":false}
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.phenomenon").value("THUNDERSTORM"))
            .andReturn().response.contentAsString

        assertEquals("THUNDERSTORM", objectMapper.readTree(body).get("phenomenon").asText())
    }

    @Test
    fun `accepts a request with no phenomenon field at all`() {
        stubForecast(code = 95)
        val user = persistUser(email = "nophenomenon@skydex.com")
        val photoUrl = uploadPhotoFor(user)

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"t","description":"d","photoUrl":"$photoUrl",
                     "latitude":-30.0,"longitude":-51.0,"locationIsMock":false}
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.phenomenon").value("THUNDERSTORM"))
    }

    @Test
    fun `answers 503 without spending the photo when open-meteo is down`() {
        stubForecastUnavailable()
        val user = persistUser(email = "meteodown@skydex.com")
        val photoUrl = uploadPhotoFor(user)

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"t","description":"d","photoUrl":"$photoUrl",
                     "latitude":-30.0,"longitude":-51.0,"locationIsMock":false}
                    """.trimIndent()
                )
        )
            .andExpect(status().isServiceUnavailable)

        // The whole retry story depends on this. `consume` runs inside CaptureCommitService,
        // which is never reached, so the photo is still citable.
        val stored = uploadedPhotoRepository.findByFilename(photoUrl.substringAfterLast('/'))!!
        assertNull(stored.consumedAt)
        assertEquals(0, weatherEventRepository.count())
    }

    @Test
    fun `retrying after a 503 succeeds with the same photo`() {
        val user = persistUser(email = "retry@skydex.com")
        val photoUrl = uploadPhotoFor(user)
        val payload = """
            {"title":"t","description":"d","photoUrl":"$photoUrl",
             "latitude":-30.0,"longitude":-51.0,"locationIsMock":false}
        """.trimIndent()

        stubForecastUnavailable()
        mockMvc.perform(
            post("/api/events").header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON).content(payload)
        ).andExpect(status().isServiceUnavailable)

        stubForecast(code = 95)
        mockMvc.perform(
            post("/api/events").header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON).content(payload)
        ).andExpect(status().isCreated)
    }

    @Test
    fun `reports why a capture was not confirmed`() {
        stubForecast(code = 95)
        val user = persistUser(email = "mocked@skydex.com")
        val photoUrl = uploadPhotoFor(user)

        mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"t","description":"d","photoUrl":"$photoUrl",
                     "latitude":-30.0,"longitude":-51.0,"locationIsMock":true}
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.unconfirmedReason").value("MOCK_LOCATION"))
            .andExpect(jsonPath("$.xpAwarded").value(0))
    }

    @Test
    fun `a confirmed capture reports no reason`() {
        stubForecast(code = 95)
        val user = persistUser(email = "confirmed@skydex.com")
        val photoUrl = uploadPhotoFor(user)

        val body = mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"t","description":"d","photoUrl":"$photoUrl",
                     "latitude":-30.0,"longitude":-51.0,"locationIsMock":false}
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.unconfirmedReason").doesNotExist())
            .andReturn().response.contentAsString

        // `doesNotExist()` above also passes for an explicit JSON `null`, so on its own it cannot
        // tell "the field was omitted" (what @JsonInclude(NON_NULL) is supposed to guarantee) apart
        // from "the field was sent as null". Checking the raw body pins the actual annotation.
        assertFalse(
            body.contains("unconfirmedReason"),
            "expected unconfirmedReason to be omitted entirely, got: $body"
        )
    }

    /**
     * The end-to-end test for stage 2 of photo validation — the anti-fraud feature this whole
     * integration exists to add. Every other test in this class that reaches
     * `PhotoAuthenticityService.contradicts` does so with a photo group that is reconcilable with
     * the stubbed weather, so nothing here previously exercised a genuine contradiction; deleting
     * `photoScores = photoAnalysis.deserialise(photo.visionScores)` in
     * `WeatherEventController.create` (replacing it with `null`) left the whole suite green.
     *
     * `uploadPhotoFor` drives a real `POST /api/photos`, so the photo's cached scores are the
     * `@BeforeEach` fixture's STORM-leaning six-group map (0.80 STORM, the rest at 0.04 each) —
     * confident enough to clear both gates (`expected < 0.10`, `top > 0.70`). Stubbing `code = 0`
     * makes the expected group CLEAR, and CLEAR's row in the contradiction matrix blocks every
     * other group, STORM included — the strictest, cleanest pairing available.
     *
     * The capture is KEPT, not rejected: 201, UNCONFIRMED, and the specific reason, both in the
     * response and in the stored row.
     */
    @Test
    fun `flags a capture whose photo confidently contradicts the observed weather`() {
        stubForecast(code = 0) // CLEAR_SKY; CLEAR admits only CLEAR in the contradiction matrix
        val user = persistUser(email = "contradicted@skydex.com")
        val photoUrl = uploadPhotoFor(user) // scored STORM 0.80 by the shared vision fixture

        val body = mockMvc.perform(
            post("/api/events")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title":"t","description":"d","photoUrl":"$photoUrl",
                     "latitude":-30.0,"longitude":-51.0,"locationIsMock":false}
                    """.trimIndent()
                )
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.phenomenon").value("CLEAR_SKY"))
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.unconfirmedReason").value("PHOTO_CONTRADICTS_WEATHER"))
            .andExpect(jsonPath("$.xpAwarded").value(0))
            .andReturn().response.contentAsString

        val id = UUID.fromString(objectMapper.readTree(body).get("id").asText())
        val stored = weatherEventRepository.findById(id).orElseThrow()
        assertEquals(UnconfirmedReason.PHOTO_CONTRADICTS_WEATHER, stored.unconfirmedReason)
        assertEquals(ValidationStatus.UNCONFIRMED, stored.validationStatus)
        assertEquals(0, stored.xpAwarded)
    }

    /**
     * Open-Meteo reporting [code] for the hour the capture will be stamped in.
     *
     * `anyDouble()`, not exact coordinates: these tests post `latitude: -30.0, longitude: -51.0`
     * (round numbers, distinct from the `-30.0346, -51.2177` idiom the pre-existing tests in this
     * class use), and an exact-coordinate stub that does not match would make `fetchHourlyForecast`
     * return null and the capture 503 — which looks exactly like the Open-Meteo-outage test passing
     * for the right reason when it is actually passing for the wrong one.
     */
    private fun stubForecast(code: Int) {
        // Truncated to the hour so the slot is always within CaptureValidationService's 90-minute
        // window of `Instant.now()`, whatever minute the suite happens to run at.
        val slot = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.HOURS)
            .toString()

        `when`(openMeteoClient.fetchHourlyForecast(anyDouble(), anyDouble())).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0,
                longitude = -51.0,
                hourly = HourlyData(
                    time = listOf(slot),
                    temperatureCelsius = listOf(21.0),
                    weatherCode = listOf(code),
                    isDay = listOf(1)
                )
            )
        )
    }

    private fun stubForecastUnavailable() {
        `when`(openMeteoClient.fetchHourlyForecast(anyDouble(), anyDouble())).thenReturn(null)
    }

    /** Uploads a minimal JPEG as [user] and returns the relative path the server assigned. */
    private fun uploadPhotoFor(user: User): String {
        val part = MockMultipartFile(
            "file", "sky.jpg", MediaType.IMAGE_JPEG_VALUE,
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        )
        val body = mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(body).get("photoUrl").asText()
    }
}
