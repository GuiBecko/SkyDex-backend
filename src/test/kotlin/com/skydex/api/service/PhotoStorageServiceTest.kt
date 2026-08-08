package com.skydex.api.service

import com.skydex.api.services.PhotoStorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the properties that keep an upload from steering where bytes are written, and the one that
 * keeps a stored value host-independent. [com.skydex.api.controller.PhotoControllerTest] covers the
 * endpoint; this covers the storage rules directly, without a Spring context, so no database or
 * container is needed.
 */
class PhotoStorageServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    private fun service() = PhotoStorageService(tempDir.toString())

    /**
     * The property that makes path traversal a non-issue: whatever the client called the file,
     * the name on disk is a generated one. Nothing of the original survives at all — the extension
     * comes from the validated content type, not from the name.
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

        val onDisk = Files.list(tempDir).use { it.toList() }
        assertEquals(listOf(stored), onDisk.map { it.fileName.toString() })
        assertTrue(jpegBytes.contentEquals(Files.readAllBytes(onDisk.single())))
    }

    /**
     * The stored value is what gets persisted in `weather_events.photo_url`, and rows are
     * immutable. If a host ever appears in this string, every historical capture is one server
     * move away from pointing at bytes nobody serves any more.
     */
    @Test
    fun `the returned url is relative so nothing host-specific can be persisted`() {
        val url = service().store(jpegBytes, "storm.jpg", "image/jpeg")

        assertTrue(url.startsWith("/api/photos/"), "expected a relative path, got $url")
        assertFalse(url.contains("://"), "a host leaked into the stored value: $url")
    }

    /**
     * `image/jpeg; charset=UTF-8` is a legitimate header. Comparing the raw value against the
     * allowlist would reject it as a non-image; the parameter must be stripped before the match.
     */
    @Test
    fun `a content type carrying parameters is still recognised as an image`() {
        val url = service().store(jpegBytes, "storm.jpg", "image/jpeg; charset=UTF-8")

        assertTrue(url.endsWith(".jpg"), "expected a .jpg name, got $url")
    }
}
