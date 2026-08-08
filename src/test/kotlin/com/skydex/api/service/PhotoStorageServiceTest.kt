package com.skydex.api.service

import com.skydex.api.errors.ConflictException
import com.skydex.api.services.PhotoStorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the two properties that keep an upload from steering where bytes are written.
 * [com.skydex.api.controller.PhotoControllerTest] covers the endpoint; this covers the storage
 * rules directly, without a Spring context, so no database or container is needed.
 */
class PhotoStorageServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    private fun service(baseUrl: String = "http://localhost:8080") =
        PhotoStorageService(tempDir.toString(), baseUrl)

    /**
     * The property that makes path traversal a non-issue: whatever the client called the file,
     * the name on disk is a generated one. Nothing of the original survives but the extension,
     * and that comes from the validated content type rather than from the name.
     */
    @Test
    fun `the client's filename never becomes the name on disk`() {
        val url = service().store(jpegBytes, "../../../etc/cron.d/pwn.sh", "image/jpeg")

        val stored = url.substringAfterLast('/')
        assertFalse(stored.contains("pwn"), "the original name leaked into $stored")
        assertTrue(
            stored.matches(Regex("[0-9a-f-]{36}\\.jpg")),
            "expected a UUID and a validated extension, got $stored"
        )
        assertEquals("http://localhost:8080/api/photos/$stored", url)

        val onDisk = Files.list(tempDir).use { it.toList() }
        assertEquals(listOf(stored), onDisk.map { it.fileName.toString() })
        assertTrue(jpegBytes.contentEquals(Files.readAllBytes(onDisk.single())))
    }

    @Test
    fun `a trailing slash on the public base url is not doubled`() {
        val url = service(baseUrl = "http://localhost:8080/").store(jpegBytes, "storm.jpg", "image/jpeg")

        assertTrue(url.startsWith("http://localhost:8080/api/photos/"), url)
    }

    @Test
    fun `resolve keeps a stored name inside the storage root`() {
        val resolved = service().resolve("abc.jpg")

        assertEquals(tempDir.toAbsolutePath().normalize().resolve("abc.jpg"), resolved)
    }

    @Test
    fun `resolve refuses a name that escapes the storage root`() {
        val error = assertThrows<ConflictException> { service().resolve("../../etc/passwd") }

        assertEquals("Invalid photo path", error.message)
    }
}
