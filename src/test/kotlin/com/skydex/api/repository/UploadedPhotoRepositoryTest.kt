package com.skydex.api.repository

import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.persistUploadedPhoto
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pins the atomicity primitive the single-use guarantee rests on.
 *
 * The HTTP-level test in `WeatherEventControllerTest` covers the sequential case — capture, then a
 * second capture citing the same photo — but it cannot tell a conditional update apart from a
 * read-then-write, because sequentially both behave identically. This can: an unconditional
 * `set consumed_at = now where id = ?` reports one changed row every time it is called, so the
 * second `assertEquals(0, ...)` below is the only thing in the suite that fails when the
 * `consumed_at is null` predicate is dropped and the race is reopened.
 */
class UploadedPhotoRepositoryTest : IntegrationTestBase() {

    @Test
    fun `consume spends a photo exactly once and reports who won`() {
        val owner = persistUser(email = "racer@skydex.com")
        val photo = persistUploadedPhoto(owner)
        val firstStamp = Instant.now()
        val secondStamp = firstStamp.plusSeconds(1)

        // The winner: one row changed.
        assertEquals(1, uploadedPhotoRepository.consume(photo.id!!, firstStamp))

        // The loser, standing in for a concurrent request that read the same null consumedAt and
        // got as far as the write. Zero rows changed is how it finds out it lost — and it is a
        // value this code can turn into a 400 with the right message, which is precisely what
        // @Version could not do.
        assertEquals(0, uploadedPhotoRepository.consume(photo.id!!, secondStamp))

        val row = uploadedPhotoRepository.findByFilename(photo.filename)
        assertNotNull(row, "the photo row disappeared")
        assertNotNull(row!!.consumedAt, "the winning consume did not stamp the row")
        // The loser must not have overwritten the winner's stamp either.
        assertTrue(
            row.consumedAt!!.isBefore(secondStamp),
            "the losing consume overwrote the stamp: ${row.consumedAt}"
        )
    }
}
