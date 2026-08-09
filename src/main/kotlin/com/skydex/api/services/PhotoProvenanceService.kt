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
 *
 * Split deliberately into two halves. [verify] is a read that answers "may this caller cite this
 * photo?" and is cheap enough to run before the capture is scored, so a bad photo costs no
 * Open-Meteo call. [consume] is the write that actually spends it, and runs after scoring, inside
 * the same transaction as the capture insert. Doing both in one step would either hold a database
 * connection across an outbound HTTP call or leave a window in which two requests can both spend
 * the same photo.
 */
@Service
class PhotoProvenanceService(private val photos: UploadedPhotoRepository) {

    /**
     * Checks that [uploaderId] may cite [photoUrl] on a capture at [now], and returns the row.
     * Reads only — the photo is not spent here; see [consume].
     *
     * Throws [BadUploadException] when the photo cannot be cited:
     * - unknown filename, or a filename that belongs to a different uploader, both raise the
     *   SAME message. The property this protects is OWNERSHIP PRIVACY, not the existence of the
     *   file: `GET /api/photos/<name>` is `permitAll` and serves uploads straight off disk, so any
     *   can already learn which filenames exist by asking for one and reading 200 versus 404.
     *   What a split message would newly disclose is *whose* photo a given filename is — an
     *   authenticated user could sweep paths scraped from a feed and learn which of them are not
     *   theirs, i.e. which belong to somebody. One message keeps that unanswerable.
     * - already spent, or older than [MAX_AGE], are the caller's OWN photos, so distinguishing
     *   them leaks nothing and each gets its own message.
     */
    fun verify(photoUrl: String, uploaderId: UUID, now: Instant): UploadedPhoto {
        val filename = photoUrl.substringAfterLast('/')
        val photo = photos.findByFilename(filename)?.takeIf { it.uploaderId == uploaderId }
            ?: throw BadUploadException("Photo is not available for this capture")

        if (photo.consumedAt != null) {
            throw BadUploadException("This photo has already been used for a capture")
        }
        if (Duration.between(photo.uploadedAt, now) > MAX_AGE) {
            throw BadUploadException("Photo has expired; take a new one")
        }

        return photo
    }

    /**
     * Spends the photo [id], stamping [now]. This is the authoritative single-use check, not
     * [verify]'s: the conditional update either changes the row or reports that somebody else got
     * there first, and only the winner returns.
     *
     * A loser here raises the same message the sequential path raises from [verify], because from
     * the caller's side the two situations are identical — the photo was already used. It has,
     * however, paid for an Open-Meteo call by this point, which is the accepted price of not
     * holding a connection open across that call. Only a client actively racing itself pays it.
     */
    fun consume(id: UUID, now: Instant) {
        if (photos.consume(id, now) == 0) {
            throw BadUploadException("This photo has already been used for a capture")
        }
    }

    private companion object {
        /**
         * The real flow uploads a photo and creates the capture seconds apart, so 30 minutes is
         * generous slack for a slow network without leaving a stock image usable a day later.
         */
        val MAX_AGE: Duration = Duration.ofMinutes(30)
    }
}
