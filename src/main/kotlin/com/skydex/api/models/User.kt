package com.skydex.api.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(nullable = false)
    var name: String = "",

    @Column(nullable = false, unique = true)
    var email: String = "",

    @Column(name = "password_hash", nullable = false)
    private var passwordHash: String = "",

    @Column(name = "joined_at", nullable = false)
    var joinedAt: Instant = Instant.now(),

    // --- Movement trail (Task 12c) --------------------------------------------------------------
    // Where this user's last capture claimed to be, and when. Written on EVERY capture, confirmed
    // or not, so a deliberately-unconfirmed capture cannot be used to park the trail somewhere
    // convenient before jumping.
    //
    // This deliberately does NOT live in `weather_events`, even though every value here is also on
    // a capture row. `DELETE /api/events/{id}` is unrestricted for the owner, so a trail derived
    // from capture rows is one the cheater can erase: capture in Tokyo, delete it, and the next
    // capture has nothing implausible to compare against. The trail has to outlive the captures,
    // so it lives here and is never deleted.
    //
    // Nullable as a group: a user who has never captured has no trail, and that absence is a real
    // state rather than a placeholder. Read them through `lastKnownPosition()` in
    // `WeatherEventController`, which only reports a position when all three are present.

    @Column(name = "last_capture_latitude")
    var lastCaptureLatitude: Double? = null,

    @Column(name = "last_capture_longitude")
    var lastCaptureLongitude: Double? = null,

    @Column(name = "last_capture_at")
    var lastCaptureAt: Instant? = null
) : UserDetails {

    override fun getAuthorities(): MutableCollection<out GrantedAuthority> =
        mutableListOf(SimpleGrantedAuthority("ROLE_USER"))

    override fun getPassword(): String = passwordHash

    override fun getUsername(): String = email

    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = true
}
