package com.skydex.api.controller

import com.skydex.api.dto.VisionAnalysis
import com.skydex.api.services.VisionClient
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class PhotoControllerTest : IntegrationTestBase() {

    /** Smallest valid JPEG: SOI marker, EOI marker. Enough to prove bytes round-trip. */
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    /** The eight-byte PNG signature, which is all the storage service inspects. */
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `stores an uploaded jpeg and returns a fetchable url`() {
        val user = persistUser(email = "photographer@skydex.com")
        val part = MockMultipartFile("file", "storm.jpg", MediaType.IMAGE_JPEG_VALUE, jpegBytes)

        val body = mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.photoUrl").exists())
            .andReturn().response.contentAsString

        val photoUrl = objectMapper.readTree(body).get("photoUrl").asText()
        // Relative, deliberately: the client hands this exact string to POST /api/events, where it
        // is persisted. A host baked in here would be frozen into every row forever.
        assertTrue(photoUrl.startsWith("/api/photos/"), "expected a relative path, got $photoUrl")
        assertFalse(photoUrl.contains("://"), "a host leaked into the upload response: $photoUrl")
        assertTrue(photoUrl.endsWith(".jpg"), "expected the extension to be preserved, got $photoUrl")

        // The stored file is reachable without authentication so Coil can render it.
        val filename = photoUrl.substringAfterLast('/')
        mockMvc.perform(get("/api/photos/{filename}", filename))
            .andExpect(status().isOk)
            // These bytes came from a client, so the browser must never be allowed to sniff a
            // content type out of them and render whatever it decides they are.
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    @Test
    fun `records the upload against the caller so a capture can later cite it`() {
        val user = persistUser(email = "provenance@skydex.com")
        val part = MockMultipartFile("file", "storm.jpg", MediaType.IMAGE_JPEG_VALUE, jpegBytes)

        val body = mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        val photoUrl = objectMapper.readTree(body).get("photoUrl").asText()
        // The row is keyed by the bare filename, not the path, because that is what
        // PhotoProvenanceService.claim derives from the photoUrl a capture cites.
        val filename = photoUrl.substringAfterLast('/')

        val recorded = uploadedPhotoRepository.findByFilename(filename)
        assertNotNull(recorded, "no UploadedPhoto row was written for $photoUrl")
        assertEquals(user.id, recorded!!.uploaderId, "the upload was recorded against the wrong user")
        // Nothing has spent it yet: a fresh upload must be claimable by a capture.
        assertNull(recorded.consumedAt, "a brand-new upload was already marked as spent")
    }

    @Test
    fun `stores an uploaded png under a png name`() {
        val user = persistUser(email = "png@skydex.com")
        val part = MockMultipartFile("file", "storm.png", MediaType.IMAGE_PNG_VALUE, pngBytes)

        val body = mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        val photoUrl = objectMapper.readTree(body).get("photoUrl").asText()
        assertTrue(photoUrl.endsWith(".png"), "expected a .png name, got $photoUrl")
    }

    @Test
    fun `rejects a non-image upload`() {
        val user = persistUser(email = "spammer@skydex.com")
        val part = MockMultipartFile("file", "payload.sh", "application/x-sh", "rm -rf /".toByteArray())

        mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Only JPEG and PNG images are accepted"))
    }

    @Test
    fun `rejects a non-image disguised as a jpeg`() {
        val user = persistUser(email = "liar@skydex.com")

        // The test above only rejects an attacker who is honest about what they are sending.
        // Content-Type is a client-written multipart header, so the interesting case is the one
        // that lies: script bytes under an image label. If this passes, the file is stored and
        // served under a .jpg name.
        val part = MockMultipartFile(
            "file", "storm.jpg", MediaType.IMAGE_JPEG_VALUE, "<html><script>alert(1)</script>".toByteArray()
        )

        mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("File is not a JPEG image"))
    }

    /** A real PNG sent under a JPEG label: the label decides the extension, so the bytes must agree. */
    @Test
    fun `rejects a png labelled as a jpeg`() {
        val user = persistUser(email = "mismatch@skydex.com")
        val part = MockMultipartFile("file", "storm.jpg", MediaType.IMAGE_JPEG_VALUE, pngBytes)

        mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("File is not a JPEG image"))
    }

    @Test
    fun `rejects an empty upload`() {
        val user = persistUser(email = "empty@skydex.com")
        val part = MockMultipartFile("file", "empty.jpg", MediaType.IMAGE_JPEG_VALUE, ByteArray(0))

        mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Photo is empty"))
    }

    @Test
    fun `refuses an anonymous upload`() {
        val part = MockMultipartFile("file", "storm.jpg", MediaType.IMAGE_JPEG_VALUE, jpegBytes)

        mockMvc.perform(multipart("/api/photos").file(part))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `reports a missing file part as a bad request`() {
        val user = persistUser(email = "forgetful@skydex.com")

        mockMvc.perform(multipart("/api/photos").header("Authorization", authHeaderFor(user)))
            .andExpect(status().isBadRequest)
    }

    // --- vision analysis at upload -----------------------------------------------------------
    //
    // CLIP never runs in this suite. The real model is exercised by the golden-set regression in
    // skydex-vision; what these tests pin is the wiring: what gets stored, and which status each
    // failure produces.

    @MockBean
    private lateinit var vision: VisionClient

    private fun analysis(outdoor: Double, top: String = "RAIN") = VisionAnalysis(
        outdoorScore = outdoor,
        phenomenonScores = mapOf(
            "CLEAR" to 0.04, "CLOUDY" to 0.04, "FOG" to 0.04,
            "RAIN" to 0.04, "SNOW" to 0.04, "STORM" to 0.04
        ) + (top to 0.80),
        model = "clip-vit-b-32-zeroshot-v1"
    )

    @BeforeEach
    fun stubVision() {
        `when`(vision.analyze(any(), any())).thenReturn(analysis(outdoor = 0.94))
    }

    @Test
    fun `stores the vision scores alongside the photo`() {
        val user = persistUser(email = "analysed@skydex.com")
        val part = MockMultipartFile("file", "storm.jpg", MediaType.IMAGE_JPEG_VALUE, jpegBytes)

        val body = mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        val filename = objectMapper.readTree(body).get("photoUrl").asText().substringAfterLast('/')
        val stored = uploadedPhotoRepository.findByFilename(filename)!!

        assertEquals(0.94, stored.visionOutdoorScore)
        assertEquals("clip-vit-b-32-zeroshot-v1", stored.visionModel)
        assertNotNull(stored.visionAnalyzedAt)
        // Stored as JSON text rather than as a JSONB column: the map is read back whole, never
        // queried into, so a column type that needs a Hibernate dialect extension buys nothing.
        assertTrue(stored.visionScores!!.contains("\"RAIN\":0.8"), stored.visionScores!!)
    }

    @Test
    fun `refuses a photo the model does not think is the sky`() {
        `when`(vision.analyze(any(), any())).thenReturn(analysis(outdoor = 0.12))
        val user = persistUser(email = "notsky@skydex.com")
        val part = MockMultipartFile("file", "wall.jpg", MediaType.IMAGE_JPEG_VALUE, jpegBytes)

        mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isUnprocessableEntity)

        // Nothing was written. Rejecting at upload rather than at capture is what keeps junk out
        // of the database entirely, and what lets the user find out before typing a title.
        assertEquals(0, uploadedPhotoRepository.count())
    }

    @Test
    fun `answers 503 when the vision service cannot be reached`() {
        `when`(vision.analyze(any(), any())).thenReturn(null)
        val user = persistUser(email = "visiondown@skydex.com")
        val part = MockMultipartFile("file", "storm.jpg", MediaType.IMAGE_JPEG_VALUE, jpegBytes)

        mockMvc.perform(
            multipart("/api/photos").file(part).header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isServiceUnavailable)

        // A 503 must cost the user nothing: no row, no file, nothing to clean up, and a retry
        // that behaves exactly like a first attempt.
        assertEquals(0, uploadedPhotoRepository.count())
    }
}
