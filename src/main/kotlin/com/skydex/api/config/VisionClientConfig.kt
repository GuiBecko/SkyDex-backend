package com.skydex.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.ClientHttpRequestFactories
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * The HTTP client `VisionClient` talks to `skydex-vision` through.
 *
 * ## Why this is a bean and not built inside the client
 *
 * `OpenMeteoClient` builds its own `RestClient` inline, and copying that shape here produces a test
 * that quietly does the wrong thing: `MockRestServiceServer.bindTo(builder)` installs its mock
 * request factory on the builder the moment it is called, so a `.requestFactory(...)` invoked
 * afterwards inside the client overrides the mock and the "unit" test reaches for a real
 * localhost:8000. Building the client here and injecting it finished keeps the timeouts real in
 * production and the mock intact in tests, with no test-only seam in the production class.
 *
 * ## Why explicit timeouts at all
 *
 * `RestClient.create(...)` inherits the JDK default of **no read timeout**. That default is the
 * dangerous one: this call runs on a Tomcat request thread, so an upstream that accepts a
 * connection and then stops answering parks one thread per upload for as long as the TCP connection
 * survives — and takes down every endpoint, including the ones that never touch this service, once
 * the pool is exhausted.
 */
@Configuration
class VisionClientConfig {

    @Bean("visionRestClient")
    fun visionRestClient(
        @Value("\${skydex.vision.base-url:http://localhost:8000}") baseUrl: String
    ): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(CONNECT_TIMEOUT)
                    .withReadTimeout(READ_TIMEOUT)
            )
        )
        .build()

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)

        /**
         * Ten seconds, against Open-Meteo's five. A warm CLIP forward pass on CPU is ~250ms, but
         * the first request after a container restart pays for lazily-built graph state and can
         * take several seconds. Failing those would 503 every upload in the minute after a deploy.
         */
        val READ_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
