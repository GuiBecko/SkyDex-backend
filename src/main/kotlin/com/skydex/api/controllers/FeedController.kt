package com.skydex.api.controllers

import com.skydex.api.dto.WeatherEventResponse
import com.skydex.api.models.User
import com.skydex.api.services.FeedService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/feed")
class FeedController(private val feed: FeedService) {

    @GetMapping
    fun myFeed(
        @AuthenticationPrincipal currentUser: User,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): List<WeatherEventResponse> = feed.forUser(currentUser, page, size)
}
