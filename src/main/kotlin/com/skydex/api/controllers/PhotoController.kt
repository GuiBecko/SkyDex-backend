package com.skydex.api.controllers

import com.skydex.api.dto.PhotoUploadResponse
import com.skydex.api.models.User
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
class PhotoController(private val photos: PhotoStorageService) {

    /**
     * [currentUser] is unused in the body, but it keeps the endpoint authenticated and makes the
     * ownership requirement visible; do not drop the parameter.
     */
    @PostMapping
    fun upload(
        @AuthenticationPrincipal currentUser: User,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<PhotoUploadResponse> {
        val url = photos.store(file.bytes, file.originalFilename, file.contentType)
        return ResponseEntity.status(HttpStatus.CREATED).body(PhotoUploadResponse(url))
    }
}
