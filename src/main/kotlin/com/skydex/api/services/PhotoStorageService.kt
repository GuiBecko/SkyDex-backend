package com.skydex.api.services

import com.skydex.api.errors.ConflictException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.UUID

@Service
class PhotoStorageService(
    @Value("\${skydex.photos.directory}") private val storageDirectory: String,
    @Value("\${skydex.photos.public-base-url}") private val publicBaseUrl: String
) {

    private val allowedContentTypes = setOf("image/jpeg", "image/png")

    private val root: Path by lazy {
        Paths.get(storageDirectory).toAbsolutePath().normalize().also { Files.createDirectories(it) }
    }

    /**
     * Writes the bytes under a freshly generated name and returns the URL clients should use.
     * The original filename is never reused — only its extension, and only after validation —
     * so a caller cannot influence the path that gets written.
     */
    fun store(bytes: ByteArray, originalFilename: String?, contentType: String?): String {
        if (bytes.isEmpty()) throw BadUploadException("Photo is empty")
        val declared = contentType?.lowercase(Locale.ROOT)
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

        return "${publicBaseUrl.trimEnd('/')}/api/photos/$filename"
    }

    /**
     * Turns a stored photo's name into an absolute path, refusing anything that escapes the
     * storage root. Nothing produced by [store] can escape it — the name is a UUID and a validated
     * extension — so this only bites on a name that came from somewhere else.
     */
    fun resolve(filename: String): Path = root.resolve(filename).normalize().also {
        if (!it.startsWith(root)) throw ConflictException("Invalid photo path")
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
