package com.skydex.api.repositories

import com.skydex.api.domain.ValidationStatus
import com.skydex.api.models.WeatherEvent
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface WeatherEventRepository : JpaRepository<WeatherEvent, UUID> {

    fun findByUserIdOrderByCapturedAtDesc(userId: UUID): List<WeatherEvent>

    fun findByUserIdInOrderByCapturedAtDesc(
        userIds: Collection<UUID>,
        pageable: Pageable
    ): List<WeatherEvent>

    fun findByUserIdAndValidationStatus(
        userId: UUID,
        validationStatus: ValidationStatus
    ): List<WeatherEvent>

    fun countByUserId(userId: UUID): Long

    fun countByUserIdAndValidationStatus(userId: UUID, validationStatus: ValidationStatus): Long

    // Filters explicitly on CONFIRMED rather than relying on the invariant that non-CONFIRMED
    // rows always carry xpAwarded = 0 (enforced by CaptureValidationService/CaptureCommitService,
    // neither of which this query can see). That invariant living elsewhere is exactly why a
    // regression there would otherwise change this total silently.
    @Query(
        "SELECT COALESCE(SUM(e.xpAwarded), 0) FROM WeatherEvent e " +
            "WHERE e.userId = :userId AND e.validationStatus = com.skydex.api.domain.ValidationStatus.CONFIRMED"
    )
    fun totalXpForUser(@Param("userId") userId: UUID): Int
}
