package com.skydex.api.controller

import com.skydex.api.dto.UpdateProfileRequest
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistEvent
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class UserControllerTest : IntegrationTestBase() {

    @Test
    fun `finds the authenticated user and returns 200`() {
        val user = persistUser(name = "Guilherme", email = "busca@test.com")

        mockMvc.perform(
            get("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(user.id.toString()))
            .andExpect(jsonPath("$.name").value("Guilherme"))
    }

    @Test
    fun `updates the authenticated user's profile and returns 200`() {
        val user = persistUser(name = "Old Name", email = "old@test.com")

        val request = UpdateProfileRequest(name = "Guilherme", email = "new@test.com")

        mockMvc.perform(
            put("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Guilherme"))
            .andExpect(jsonPath("$.email").value("new@test.com"))
    }

    @Test
    fun `deletes the authenticated user and returns 204 No Content`() {
        val user = persistUser(name = "User to delete", email = "delete@test.com")
        persistEvent(owner = user, title = "Orphan risk", description = "Should go with the user", photoUrl = "http://photo.jpg")

        mockMvc.perform(
            delete("/api/users/me")
                .header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isNoContent)

        val stillExists = userRepository.existsById(user.id!!)
        assert(!stillExists)

        val orphanedEvents = weatherEventRepository.findByUserIdOrderByCapturedAtDesc(user.id!!)
        assert(orphanedEvents.isEmpty())
    }

    @Test
    fun `user responses never expose the password hash`() {
        val user = persistUser(name = "Leak Check", email = "leak@skydex.com", password = "plain-text-secret")

        mockMvc.perform(
            get("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Leak Check"))
            .andExpect(jsonPath("$.email").value("leak@skydex.com"))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.authorities").doesNotExist())
            .andExpect(jsonPath("$.enabled").doesNotExist())
    }

    @Test
    fun `me returns the authenticated user`() {
        val user = persistUser(name = "Self", email = "self@skydex.com")

        mockMvc.perform(
            get("/api/users/me").header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(user.id!!.toString()))
            .andExpect(jsonPath("$.name").value("Self"))
            .andExpect(jsonPath("$.password").doesNotExist())
    }

    @Test
    fun `rejects a profile update that would take another user's email`() {
        persistUser(email = "taken@skydex.com")
        val user = persistUser(email = "mover@skydex.com")

        val payload = UpdateProfileRequest(name = "Mover", email = "taken@skydex.com")

        mockMvc.perform(
            put("/api/users/me")
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("Email already registered"))
    }
}
