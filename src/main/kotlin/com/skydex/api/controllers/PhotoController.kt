package com.skydex.api.controllers

import com.skydex.api.dto.PhotoUploadResponse
import com.skydex.api.models.UploadedPhoto
import com.skydex.api.models.User
import com.skydex.api.repositories.UploadedPhotoRepository
import com.skydex.api.services.PhotoStorageService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/photos")
class PhotoController(
    private val photos: PhotoStorageService,
    private val uploadedPhotos: UploadedPhotoRepository
) {

    /**
     * [currentUser] used to be unused in the body — a parameter kept only to keep the endpoint
     * authenticated and document an ownership requirement nothing enforced yet. Task 12b makes it
     * load-bearing: it is recorded as the [UploadedPhoto] row's uploader, which is what
     * `PhotoProvenanceService.claim` later checks before `POST /api/events` may cite the returned
     * path.
     */
    @PostMapping
    fun upload(
        @AuthenticationPrincipal currentUser: User,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<PhotoUploadResponse> {
        val url = photos.store(file.bytes, file.originalFilename, file.contentType)
        uploadedPhotos.save(
            UploadedPhoto(
                filename = url.substringAfterLast('/'),
                uploaderId = currentUser.id!!
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(PhotoUploadResponse(url))
    }
}
