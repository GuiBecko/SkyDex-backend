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

/**
 * The three movement-trail columns, read as values rather than as a `User`.
 *
 * All three are nullable together — a user who has never captured has no trail — and callers are
 * expected to treat any of them being absent as "no trail".
 */
interface CaptureTrailRow {
    val latitude: Double?
    val longitude: Double?
    val at: Instant?
}

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?

    /**
     * Reads [id]'s movement trail holding an exclusive row lock until the surrounding transaction
     * ends, so the caller can read it, decide against it and write it back with nothing
     * interleaving. Meaningless — and a lock held for no reason — outside a transaction. This is
     * the serialisation point for concurrent captures by one user; see
     * `CaptureCommitService.commit` for why an unsynchronised read is not enough.
     *
     * **It returns three scalars rather than the `User` entity, and that is the entire point.**
     *
     * The hazard a projection avoids is what a *request-scoped* persistence context does to an
     * entity read. When one EntityManager spans the whole request, and a `User` for this id is
     * already in it, an entity query returns the managed instance: the `select … for update`
     * executes and the row really is locked, but the fields handed back are the ones already in
     * memory, not the ones just read. Every concurrent capture would then re-check against its own
     * stale trail and the lock would buy nothing.
     *
     * **That failure mode was measured, not assumed**: with an entity read, adding a single
     * `users.findById(...)` to `WeatherEventController.create` makes the concurrency tests in
     * `CaptureTrailOpenInViewTest` fail outright, four confirmed captures out of a simultaneous
     * four.
     *
     * As of Task 13, `spring.jpa.open-in-view=false` in **every** profile — `application.properties`
     * and `application-test.properties` both set it — so no request-scoped EntityManager exists and
     * that hazard is not live today. The projection is not here because of the current setting,
     * though, and must not be undone if someone reads the setting and concludes it is redundant.
     * Scalars are not entities: there is no identity map to substitute anything from, and the
     * values are necessarily those of the row just locked. It is correct whatever `open-in-view` is
     * set to and whatever else the request has already loaded, which is precisely why it was chosen
     * over an argument that depends on a config flag staying put. `CaptureTrailOpenInViewTest` runs
     * these paths with the flag flipped back on so that the guarantee is pinned rather than
     * inherited. Do not "tidy" this back into returning a `User`.
     *
     * Typed via [CaptureTrailRow] rather than as an `Array<Any?>` of columns, which was the first
     * attempt and was quietly broken: Spring Data reads an array return type as "a collection of
     * rows", so a three-column query came back as `Object[]{ Object[]{lat, lon, at} }` and index 0
     * held the inner array rather than the latitude. A safe-looking `as? Double` then turned that
     * into a null, the trail read as absent, and the whole re-check passed everything. A declared
     * projection has no index to get wrong and converts by type rather than by cast.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select u.lastCaptureLatitude as latitude,
               u.lastCaptureLongitude as longitude,
               u.lastCaptureAt as at
          from User u
         where u.id = :id
        """
    )
    fun lockTrailForUpdate(@Param("id") id: UUID): CaptureTrailRow?

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
     * (Do not reason about whether that entity is managed or detached. Since Task 13
     * `open-in-view` is off in every profile, so today it is detached everywhere — but that is a
     * config flag, and the correctness of this write should not rest on one. A targeted UPDATE is
     * right either way, which is the point of choosing it.)
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
