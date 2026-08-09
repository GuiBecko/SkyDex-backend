package com.skydex.api.repositories

import com.skydex.api.models.UserBadge
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserBadgeRepository : JpaRepository<UserBadge, UUID> {
    fun findByUserId(userId: UUID): List<UserBadge>
}
