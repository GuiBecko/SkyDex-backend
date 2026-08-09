package com.skydex.api.controller

import com.skydex.api.dto.FriendRequestBody
import com.skydex.api.models.FriendshipStatus
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

class FriendControllerTest : IntegrationTestBase() {

    private fun requestBody(email: String) = objectMapper.writeValueAsString(FriendRequestBody(email))

    @Test
    fun `sends a friend request and lists it for the recipient`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        val bob = persistUser(name = "Bob", email = "bob@skydex.com")

        mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("bob@skydex.com"))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.requesterName").value("Alice"))

        mockMvc.perform(get("/api/friends/requests").header("Authorization", authHeaderFor(bob)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].requesterEmail").value("alice@skydex.com"))

        // The sender does not see their own outgoing request in the incoming list.
        mockMvc.perform(get("/api/friends/requests").header("Authorization", authHeaderFor(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `accepting a request makes both users friends`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        val bob = persistUser(name = "Bob", email = "bob@skydex.com")

        val created = mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("bob@skydex.com"))
        ).andReturn().response.contentAsString
        val requestId = objectMapper.readTree(created).get("id").asText()

        mockMvc.perform(
            post("/api/friends/requests/{id}/accept", requestId)
                .header("Authorization", authHeaderFor(bob))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Alice"))

        mockMvc.perform(get("/api/friends").header("Authorization", authHeaderFor(bob)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].email").value("alice@skydex.com"))

        // Friendship is symmetric: Alice sees Bob too.
        mockMvc.perform(get("/api/friends").header("Authorization", authHeaderFor(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].email").value("bob@skydex.com"))

        assertEquals(
            FriendshipStatus.ACCEPTED,
            friendshipRepository.findById(UUID.fromString(requestId)).orElseThrow().status
        )
    }

    @Test
    fun `only the recipient can accept a request`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        persistUser(name = "Bob", email = "bob@skydex.com")
        val mallory = persistUser(name = "Mallory", email = "mallory@skydex.com")

        val created = mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("bob@skydex.com"))
        ).andReturn().response.contentAsString
        val requestId = objectMapper.readTree(created).get("id").asText()

        mockMvc.perform(
            post("/api/friends/requests/{id}/accept", requestId)
                .header("Authorization", authHeaderFor(mallory))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("This request was not sent to you"))
    }

    @Test
    fun `refuses to befriend yourself`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")

        mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("alice@skydex.com"))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("You cannot add yourself"))
    }

    @Test
    fun `refuses a duplicate request in either direction`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        val bob = persistUser(name = "Bob", email = "bob@skydex.com")

        mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("bob@skydex.com"))
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("bob@skydex.com"))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("You already have a pending or accepted request with this user"))

        mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(bob))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("alice@skydex.com"))
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `returns 404 for an unknown email`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")

        mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("nobody@skydex.com"))
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("No user with that email"))
    }

    @Test
    fun `declining a request removes it from the recipient's list`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        val bob = persistUser(name = "Bob", email = "bob@skydex.com")

        val created = mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("bob@skydex.com"))
        ).andReturn().response.contentAsString
        val requestId = objectMapper.readTree(created).get("id").asText()

        mockMvc.perform(
            delete("/api/friends/requests/{id}", requestId)
                .header("Authorization", authHeaderFor(bob))
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/friends/requests").header("Authorization", authHeaderFor(bob)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))

        // Declining frees the pair: Alice can ask again rather than being locked out by the
        // duplicate check forever.
        mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("bob@skydex.com"))
        ).andExpect(status().isCreated)
    }

    @Test
    fun `a stranger cannot decline someone else's request`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        persistUser(name = "Bob", email = "bob@skydex.com")
        val mallory = persistUser(name = "Mallory", email = "mallory@skydex.com")

        val created = mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("bob@skydex.com"))
        ).andReturn().response.contentAsString
        val requestId = objectMapper.readTree(created).get("id").asText()

        mockMvc.perform(
            delete("/api/friends/requests/{id}", requestId)
                .header("Authorization", authHeaderFor(mallory))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("This request is not yours"))
    }
}
