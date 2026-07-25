package com.skydex.api.repositories

import com.skydex.api.models.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository: JpaRepository<User, UUID> {}