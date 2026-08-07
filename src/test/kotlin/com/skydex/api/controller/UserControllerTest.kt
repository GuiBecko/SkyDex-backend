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
import java.util.UUID

class UserControllerTest : IntegrationTestBase() {

    @Test
    fun `finds a user by id and returns 200`() {
        val user = persistUser(name = "Guilherme", email = "busca@test.com")

        mockMvc.perform(
            get("/api/users/{id}", user.id!!)
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(user.id.toString()))
            .andExpect(jsonPath("$.name").value("Guilherme"))
    }

    @Test
    fun `updates an existing user and returns 200`() {
        val user = persistUser(name = "Old Name", email = "old@test.com")

        val request = UpdateProfileRequest(name = "Guilherme", email = "new@test.com")

        mockMvc.perform(
            put("/api/users/{id}", user.id!!)
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Guilherme"))
            .andExpect(jsonPath("$.email").value("new@test.com"))
    }

    @Test
    fun `deletes an existing user and returns 204 No Content`() {
        val user = persistUser(name = "User to delete", email = "delete@test.com")

        mockMvc.perform(
            delete("/api/users/{id}", user.id!!)
                .header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isNoContent)

        val stillExists = userRepository.existsById(user.id!!)
        assert(!stillExists)
    }

    @Test
    fun `returns 404 Not Found when looking up an id that does not exist`() {
        val unknownId = UUID.randomUUID()
        val requester = persistUser(email = "requester@skydex.com")

        mockMvc.perform(
            get("/api/users/{id}", unknownId)
                .header("Authorization", authHeaderFor(requester))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `lists a user's events and returns 200`() {
        val user = persistUser(email = "events-owner@skydex.com")
        persistEvent(owner = user, title = "Heavy Rain", description = "Storm in the region", photoUrl = "http://photo1.jpg")
        persistEvent(owner = user, title = "Hail", description = "Small ice stones", photoUrl = "http://photo2.jpg")

        mockMvc.perform(
            get("/api/users/{id}/events", user.id!!)
                .header("Authorization", authHeaderFor(user))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `user responses never expose the password hash`() {
        val user = persistUser(name = "Leak Check", email = "leak@skydex.com", password = "plain-text-secret")

        mockMvc.perform(
            get("/api/users/{id}", user.id!!)
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
}
