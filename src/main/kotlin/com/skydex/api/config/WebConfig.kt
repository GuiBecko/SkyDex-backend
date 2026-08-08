package com.skydex.api.config

import com.skydex.api.services.PhotoStorageService
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Serves uploaded capture photos straight off disk. `GET /api/photos/{filename}` is permitted
 * anonymously in SecurityConfig so the Android image loader can fetch them.
 *
 * (The handler pattern is spelled out in code below rather than here: Kotlin block comments nest,
 * so a literal `/`+`**` inside a KDoc opens a comment that is never closed.)
 */
@Configuration
class WebConfig(private val photos: PhotoStorageService) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // The trailing slash is load-bearing: a location without one is treated as a file and
        // every lookup resolves against its parent directory instead of inside it.
        val location = photos.directory().toUri().toString().trimEnd('/') + "/"
        registry
            .addResourceHandler("/api/photos/**")
            .addResourceLocations(location)
    }
}
