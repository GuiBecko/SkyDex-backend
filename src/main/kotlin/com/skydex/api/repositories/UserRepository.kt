package com.skydex.api.repositories

import com.skydex.api.models.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?

    /**
     * Moves [id]'s movement trail to where their latest capture claimed to be.
     *
     * A targeted UPDATE rather than load-mutate-save on purpose. The `User` the request holds came
     * from `SecurityFilter` and is detached (`open-in-view` is off in tests, and this runs inside
     * `CaptureCommitService`'s transaction either way), so saving it back would write every one of
     * its fields from a snapshot taken before the request began — quietly reverting a concurrent
     * change to, say, the display name. Three columns is all this needs to touch.
     */
    @Modifying
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
}
