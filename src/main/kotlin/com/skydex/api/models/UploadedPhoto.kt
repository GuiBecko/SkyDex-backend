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
 *
 * The four `vision*` fields cache what `skydex-vision` said about this photo when it was
 * uploaded; see their own KDoc below for why they exist and why all four are nullable.
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
    var consumedAt: Instant? = null,

    /**
     * What `skydex-vision` said about this photograph when it was uploaded, cached so the model
     * never runs twice for one image.
     *
     * All four are nullable and all four are written together or not at all. Null means the photo
     * was uploaded before analysis existed, and `PhotoAuthenticityService` reads that as "no
     * opinion" rather than as a failed check — a photo must never be punished for a check that did
     * not run when it was taken.
     *
     * The window in which that can happen is bounded by [PhotoProvenanceService.MAX_AGE]: thirty
     * minutes after this ships, no unanalysed photo is citable by any capture.
     */
    @Column(name = "vision_outdoor_score")
    var visionOutdoorScore: Double? = null,

    /**
     * `phenomenon_scores` as JSON text, keyed by [com.skydex.api.domain.VisualGroup] name.
     *
     * Text and not JSONB: the map is read back whole and never queried into, so a column type
     * needing a Hibernate dialect extension would buy nothing and cost a dependency.
     */
    @Column(name = "vision_scores", columnDefinition = "TEXT")
    var visionScores: String? = null,

    /**
     * The model name that produced the scores, e.g. `clip-vit-b-32-probe-v1`.
     *
     * Recorded because a retrained model changes what these numbers mean. Without it, a capture
     * scored by a model that has since been replaced is indistinguishable from one scored by the
     * current one, and there is no way to re-examine a disputed verdict.
     */
    @Column(name = "vision_model", length = 64)
    var visionModel: String? = null,

    @Column(name = "vision_analyzed_at")
    var visionAnalyzedAt: Instant? = null
)
