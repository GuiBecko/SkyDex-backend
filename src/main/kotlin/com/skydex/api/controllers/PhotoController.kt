package com.skydex.api.controllers

import com.skydex.api.dto.PhotoUploadResponse
import com.skydex.api.models.UploadedPhoto
import com.skydex.api.models.User
import com.skydex.api.repositories.UploadedPhotoRepository
import com.skydex.api.services.PhotoAnalysisService
import com.skydex.api.services.PhotoStorageService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

@RestController
@RequestMapping("/api/photos")
class PhotoController(
    private val photos: PhotoStorageService,
    private val uploadedPhotos: UploadedPhotoRepository,
    private val analysis: PhotoAnalysisService
) {

    /**
     * [currentUser] is load-bearing: it is recorded as the [UploadedPhoto] row's uploader, which is
     * what `PhotoProvenanceService.verify` later checks before `POST /api/events` may cite the
     * returned path.
     *
     * ## Order of operations
     *
     * Analysis runs **before** anything is written to disk or to the database, and that order is
     * the whole reason a rejected or unanalysable photo costs nothing. A 422 or a 503 raised here
     * leaves no file, no row, and nothing for a cleanup sweep to find later.
     *
     * The bytes are read once, into `file.bytes`, and handed to both the analyser and the storage
     * service. Reading the multipart stream twice would fail on the second read.
     */
    @PostMapping
    fun upload(
        @AuthenticationPrincipal currentUser: User,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<PhotoUploadResponse> {
        val bytes = file.bytes
        val filename = file.originalFilename ?: "upload"

        // Throws 422 (not the sky) or 503 (model unreachable). Nothing is persisted yet.
        val scored = analysis.analyze(bytes, filename)

        val url = photos.store(bytes, file.originalFilename, file.contentType)
        uploadedPhotos.save(
            UploadedPhoto(
                filename = url.substringAfterLast('/'),
                uploaderId = currentUser.id!!,
                visionOutdoorScore = scored.outdoorScore,
                visionScores = analysis.serialise(scored),
                visionModel = scored.model,
                visionAnalyzedAt = Instant.now()
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(PhotoUploadResponse(url))
    }
}
