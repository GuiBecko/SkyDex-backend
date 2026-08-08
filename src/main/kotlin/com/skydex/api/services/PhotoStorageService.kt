package com.skydex.api.services

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.UUID

@Service
class PhotoStorageService(
    @Value("\${skydex.photos.directory}") private val storageDirectory: String
) {

    private val allowedContentTypes = setOf("image/jpeg", "image/png")

    private val root: Path by lazy {
        Paths.get(storageDirectory).toAbsolutePath().normalize().also { Files.createDirectories(it) }
    }

    /**
     * Writes the bytes under a freshly generated name and returns the path clients should use.
     *
     * **Nothing from [originalFilename] is used at all** — not even its extension, which comes
     * from the *validated* content type. The parameter is kept only to document that the client's
     * name is deliberately ignored, which is what makes path traversal a non-issue here: the
     * caller cannot influence the path that gets written, however the file is named.
     */
    fun store(bytes: ByteArray, originalFilename: String?, contentType: String?): String {
        if (bytes.isEmpty()) throw BadUploadException("Photo is empty")
        // A content type may legitimately carry parameters (`image/jpeg; charset=UTF-8`).
        // Comparing the raw header against the allowlist would reject those as non-images.
        val declared = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (declared !in allowedContentTypes) {
            throw BadUploadException("Only JPEG and PNG images are accepted")
        }

        // The Content-Type above is a multipart header — the client writes it, so on its own it
        // proves nothing. Without this check anyone can POST a shell script, an HTML page or a zip
        // labelled `image/jpeg` and have it stored and served under a .jpg name. Verifying the
        // leading bytes is what turns the claim into something checked.
        val extension = when (declared) {
            "image/png" -> {
                if (!bytes.startsWith(PNG_MAGIC)) throw BadUploadException("File is not a PNG image")
                "png"
            }
            else -> {
                if (!bytes.startsWith(JPEG_MAGIC)) throw BadUploadException("File is not a JPEG image")
                "jpg"
            }
        }
        val filename = "${UUID.randomUUID()}.$extension"
        Files.write(root.resolve(filename), bytes)

        // RELATIVE, deliberately. The client hands this string back to `POST /api/events`, where it
        // is persisted in `weather_events.photo_url` — and a row is immutable once written. Baking
        // an absolute host in would mean a new DHCP lease, a different laptop, or any real
        // deployment leaves every historical capture addressed to a host that no longer serves
        // those bytes, with the JPEGs intact and unreachable on disk. Config can be re-pointed;
        // written rows cannot. The base URL is a read-side display concern, so it is applied at
        // the response boundary instead — see `WeatherEventResponse.withAbsolutePhotoUrl`.
        return "/api/photos/$filename"
    }

    fun directory(): Path = root

    private companion object {
        /** JPEG files begin FF D8 FF; PNG files begin 89 50 4E 47 0D 0A 1A 0A. */
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}

class BadUploadException(message: String) : RuntimeException(message)
