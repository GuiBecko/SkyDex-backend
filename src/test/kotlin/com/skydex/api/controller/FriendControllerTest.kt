package com.skydex.api.controller

import com.skydex.api.dto.FriendRequestBody
import com.skydex.api.models.FriendshipStatus
import com.skydex.api.models.User
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    /** Sends an invite and returns the `friendships` row id, which is what every route below takes. */
    private fun invite(from: User, toEmail: String): String {
        val created = mockMvc.perform(
            post("/api/friends/requests")
                .header("Authorization", authHeaderFor(from))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(toEmail))
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return objectMapper.readTree(created).get("id").asText()
    }

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

    @Test
    fun `the friends list carries the id the delete endpoint takes`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        val bob = persistUser(name = "Bob", email = "bob@skydex.com")

        val requestId = invite(alice, "bob@skydex.com")
        mockMvc.perform(
            post("/api/friends/requests/{id}/accept", requestId)
                .header("Authorization", authHeaderFor(bob))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.friendshipId").value(requestId))

        // The value the client reads off the list — not a user id — is the row id, and it is the
        // same one the accept response returned. Without this the app can list friends and remove
        // none of them.
        val listed = mockMvc.perform(get("/api/friends").header("Authorization", authHeaderFor(bob)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].friendshipId").value(requestId))
            .andReturn().response.contentAsString
        val friendshipId = objectMapper.readTree(listed).get(0).get("friendshipId").asText()

        assertEquals(alice.id.toString(), objectMapper.readTree(listed).get(0).get("userId").asText())
        assertNotEquals(friendshipId, objectMapper.readTree(listed).get(0).get("userId").asText())
    }

    @Test
    fun `either party can unfriend, and it disappears for both`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        val bob = persistUser(name = "Bob", email = "bob@skydex.com")

        val requestId = invite(alice, "bob@skydex.com")
        mockMvc.perform(
            post("/api/friends/requests/{id}/accept", requestId)
                .header("Authorization", authHeaderFor(bob))
        ).andExpect(status().isOk)

        // The id comes off the friends list, not off the accept response, because that is the only
        // route the app has: it renders this list and deletes the row the row itself named. Reading
        // it from the accept response would pass even if the list served a user id.
        val listed = mockMvc.perform(get("/api/friends").header("Authorization", authHeaderFor(alice)))
            .andReturn().response.contentAsString
        val friendshipId = objectMapper.readTree(listed).get(0).get("friendshipId").asText()

        // Alice is the *requester*, not the addressee: the party who cannot accept can still
        // unfriend. This is the half of `decline` that had no client until `friendshipId` existed.
        mockMvc.perform(
            delete("/api/friends/requests/{id}", friendshipId)
                .header("Authorization", authHeaderFor(alice))
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/friends").header("Authorization", authHeaderFor(alice)))
            .andExpect(jsonPath("$.length()").value(0))
        mockMvc.perform(get("/api/friends").header("Authorization", authHeaderFor(bob)))
            .andExpect(jsonPath("$.length()").value(0))

        assertTrue(friendshipRepository.findById(UUID.fromString(friendshipId)).isEmpty)
    }

    @Test
    fun `a stranger cannot unfriend an accepted friendship`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        val bob = persistUser(name = "Bob", email = "bob@skydex.com")
        val mallory = persistUser(name = "Mallory", email = "mallory@skydex.com")

        val friendshipId = invite(alice, "bob@skydex.com")
        mockMvc.perform(
            post("/api/friends/requests/{id}/accept", friendshipId)
                .header("Authorization", authHeaderFor(bob))
        ).andExpect(status().isOk)

        mockMvc.perform(
            delete("/api/friends/requests/{id}", friendshipId)
                .header("Authorization", authHeaderFor(mallory))
        ).andExpect(status().isForbidden)

        mockMvc.perform(get("/api/friends").header("Authorization", authHeaderFor(bob)))
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `the pending count is the number of invites waiting on you`() {
        val alice = persistUser(name = "Alice", email = "alice@skydex.com")
        val bob = persistUser(name = "Bob", email = "bob@skydex.com")
        val carol = persistUser(name = "Carol", email = "carol@skydex.com")

        mockMvc.perform(get("/api/friends/requests/count").header("Authorization", authHeaderFor(bob)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(0))

        val fromAlice = invite(alice, "bob@skydex.com")
        invite(carol, "bob@skydex.com")

        mockMvc.perform(get("/api/friends/requests/count").header("Authorization", authHeaderFor(bob)))
            .andExpect(jsonPath("$.count").value(2))

        // Outgoing invites never light up the sender's own badge.
        mockMvc.perform(get("/api/friends/requests/count").header("Authorization", authHeaderFor(alice)))
            .andExpect(jsonPath("$.count").value(0))

        // Answering one clears one: the badge counts PENDING, not every row the user is in.
        mockMvc.perform(
            post("/api/friends/requests/{id}/accept", fromAlice)
                .header("Authorization", authHeaderFor(bob))
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/friends/requests/count").header("Authorization", authHeaderFor(bob)))
            .andExpect(jsonPath("$.count").value(1))
    }
}
