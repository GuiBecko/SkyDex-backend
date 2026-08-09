package com.skydex.api.repositories

import com.skydex.api.models.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?

    /**
     * Loads [id] holding an exclusive row lock until the surrounding transaction ends, so that the
     * caller can read the movement trail, decide against it and write it back with nothing else
     * interleaving. Meaningless — and a lock held for no reason — outside a transaction.
     *
     * This is the serialisation point for concurrent captures by one user; see
     * `CaptureCommitService.commit` for why an unsynchronised read is not enough.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): User?

    /**
     * Moves [id]'s movement trail to where their latest capture claimed to be.
     *
     * A targeted UPDATE rather than load-mutate-save on purpose, and the reason is worth stating
     * because the codebase got it wrong once already (see [updateProfile]). The `User` a request
     * holds came from `SecurityFilter` at the start of that request; `save`ing it writes back every
     * column from that snapshot. For a capture that would mean restoring an older trail — the exact
     * rewind this check exists to prevent — and for any other handler it means clobbering whatever
     * changed in between. Three columns is all this needs to touch.
     *
     * (Do not reason about whether that entity is managed or detached: `open-in-view` is off in
     * tests and left at Spring Boot's default of `true` in dev, so it is detached in one and
     * managed in the other. A targeted UPDATE is correct either way, which is the point of
     * choosing it.)
     *
     * `@Transactional` so the write stands on its own when called directly; called from inside
     * `CaptureCommitService.commit` it simply joins that transaction (REQUIRED) and commits with
     * the capture, which is what keeps a capture and its trail from diverging.
     */
    @Modifying
    @Transactional
    @Query(
        """
        update User u
           set u.lastCaptureLatitude = :latitude,
               u.lastCaptureLongitude = :longitude,
               u.lastCaptureAt = :at
         where u.id = :id
        """
    )
    fun recordLastCapture(
        @Param("id") id: UUID,
        @Param("latitude") latitude: Double,
        @Param("longitude") longitude: Double,
        @Param("at") at: Instant
    )

    /**
     * Writes the two fields a profile edit is allowed to change, and nothing else.
     *
     * `UserController.updateMe` used to mutate the authenticated `User` and `save` it. That was
     * harmless while every column on the row was profile data; it stopped being harmless the moment
     * the movement trail moved onto this entity. A profile update carrying a pre-request snapshot
     * would restore an OLDER `last_capture_at` over a trail a concurrent capture had just advanced,
     * and an older timestamp means a larger reachable radius — a profile edit would have become a
     * way to buy travel budget. Security state and wholesale entity writes do not mix.
     */
    @Modifying
    @Transactional
    @Query("update User u set u.name = :name, u.email = :email where u.id = :id")
    fun updateProfile(
        @Param("id") id: UUID,
        @Param("name") name: String,
        @Param("email") email: String
    )
}
