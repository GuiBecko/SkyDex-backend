package com.skydex.api.services

import com.skydex.api.dto.WeatherEventResponse
import com.skydex.api.models.User
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

@Service
class FeedService(
    private val events: WeatherEventRepository,
    private val users: UserRepository,
    private val friendships: FriendshipService,
    /**
     * Injected here rather than at a controller, because this service returns finished
     * `WeatherEventResponse`s and there is no mapping step in `FeedController` to hang it on —
     * which is exactly the case `WeatherEventResponse.from`'s KDoc describes when it explains why
     * `baseUrl` is a required parameter. The property is `skydex.photos.public-base-url`, the same
     * one `WeatherEventController` reads.
     */
    @Value("\${skydex.photos.public-base-url}") private val publicBaseUrl: String
) {

    /**
     * The caller's own captures plus those of accepted friends, newest first.
     * Unconfirmed captures are included — the response carries validationStatus so the
     * client can badge them, and hiding a friend's honest miss would be unfriendly.
     */
    fun forUser(user: User, page: Int, size: Int): List<WeatherEventResponse> {
        val visibleAuthors = friendships.friendIds(user.id!!) + user.id!!

        val pageRequest = PageRequest.of(maxOf(page, 0), size.coerceIn(1, MAX_PAGE_SIZE))
        val captures = events.findByUserIdInOrderByCapturedAtDesc(visibleAuthors, pageRequest)

        // One query for every author on the page, rather than one per capture.
        val authorsById = users.findAllById(captures.map { it.userId }.distinct())
            .associateBy { it.id }

        return captures.mapNotNull { capture ->
            authorsById[capture.userId]?.let { WeatherEventResponse.from(capture, it, publicBaseUrl) }
        }
    }

    private companion object {
        const val MAX_PAGE_SIZE = 50
    }
}
