package com.skydex.api.controllers

import com.skydex.api.dto.SkyDexResponse
import com.skydex.api.models.User
import com.skydex.api.services.SkyDexService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/skydex")
class SkyDexController(private val skyDex: SkyDexService) {

    @GetMapping
    fun mine(@AuthenticationPrincipal currentUser: User): SkyDexResponse =
        skyDex.forUser(currentUser.id!!)
}
