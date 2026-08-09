package com.skydex.api.repositories

import com.skydex.api.models.UploadedPhoto
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UploadedPhotoRepository : JpaRepository<UploadedPhoto, UUID> {
    fun findByFilename(filename: String): UploadedPhoto?
}
