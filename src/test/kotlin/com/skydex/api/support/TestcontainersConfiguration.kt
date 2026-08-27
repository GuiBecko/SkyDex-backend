package com.skydex.api.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Starts a throwaway Postgres for the test run, on the same image the compose file runs.
 *
 * This used to be `postgis/postgis` because `hibernate-spatial` was on the classpath and made
 * Hibernate auto-select the PostGIS dialect, so the tests had to match. That dependency is gone
 * -- nothing imported it, no column is a geometry, and distance is haversine in Kotlin
 * (`TravelPlausibility`) -- so the dialect is now plain PostgreSQL and this image follows it.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16"))
}
