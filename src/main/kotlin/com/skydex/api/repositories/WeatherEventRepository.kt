package com.skydex.api.repositories

import com.skydex.api.models.WeatherEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface WeatherEventRepository : JpaRepository<WeatherEvent, UUID> {
    fun findByUserIdOrderByCapturedAtDesc(userId: UUID): List<WeatherEvent>
}
