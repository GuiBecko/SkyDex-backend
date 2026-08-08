package com.skydex.api.controller

import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        assertTrue(photoUrl.contains("/api/photos/"), "expected a /api/photos/ URL, got $photoUrl")
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
}
