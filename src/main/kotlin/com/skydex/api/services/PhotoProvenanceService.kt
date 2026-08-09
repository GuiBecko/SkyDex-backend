package com.skydex.api.services

import com.skydex.api.models.UploadedPhoto
import com.skydex.api.repositories.UploadedPhotoRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Enforces that a capture cites a photo its own author actually took: a real, fresh, single-use
 * upload rather than an arbitrary photos path that merely happens to exist.
 */
@Service
class PhotoProvenanceService(private val photos: UploadedPhotoRepository) {

    /**
     * Claims the photo named by [photoUrl] on behalf of [uploaderId], stamping [now] as the
     * instant it was spent, and returns the row.
     *
     * Throws [BadUploadException] when the photo cannot be claimed:
     * - unknown filename, or a filename that belongs to a different uploader, both raise the
     *   SAME message. Telling those two apart would turn this endpoint into an oracle an attacker
     *   could use to enumerate which photo filenames exist on the server.
     * - already spent, or older than [MAX_AGE], are the caller's OWN photos, so distinguishing
     *   them leaks nothing and each gets its own message.
     */
    fun claim(photoUrl: String, uploaderId: UUID, now: Instant): UploadedPhoto {
        val filename = photoUrl.substringAfterLast('/')
        val photo = photos.findByFilename(filename)?.takeIf { it.uploaderId == uploaderId }
            ?: throw BadUploadException("Photo is not available for this capture")

        if (photo.consumedAt != null) {
            throw BadUploadException("This photo has already been used for a capture")
        }
        if (Duration.between(photo.uploadedAt, now) > MAX_AGE) {
            throw BadUploadException("Photo has expired; take a new one")
        }

        photo.consumedAt = now
        return photos.save(photo)
    }

    private companion object {
        /**
         * The real flow uploads a photo and creates the capture seconds apart, so 30 minutes is
         * generous slack for a slow network without leaving a stock image usable a day later.
         */
        val MAX_AGE: Duration = Duration.ofMinutes(30)
    }
}
