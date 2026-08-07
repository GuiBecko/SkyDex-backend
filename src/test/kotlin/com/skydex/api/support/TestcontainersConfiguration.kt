package com.skydex.api.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Starts a throwaway PostGIS database for the test run. The PostGIS image is used instead of
 * plain postgres so that tests exercise the same dialect Hibernate picks in development
 * (hibernate-spatial is on the classpath).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5")
                .asCompatibleSubstituteFor("postgres")
        )
}
