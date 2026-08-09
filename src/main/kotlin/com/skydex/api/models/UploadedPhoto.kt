package com.skydex.api.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Records that [uploaderId] uploaded a photo reachable at `/api/photos/<filename>`, so
 * `PhotoProvenanceService.claim` can bind a capture to a photo its own author actually took
 * instead of an arbitrary path someone else's upload happens to have produced.
 *
 * [consumedAt] stays null until a capture spends the photo. A photo is single-use, so a non-null
 * value here is exactly what makes a second citation of the same filename fail.
 */
@Entity
@Table(name = "uploaded_photos")
class UploadedPhoto(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(nullable = false, unique = true)
    var filename: String = "",

    @Column(name = "uploader_id", nullable = false)
    var uploaderId: UUID,

    @Column(name = "uploaded_at", nullable = false)
    var uploadedAt: Instant = Instant.now(),

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null
)
