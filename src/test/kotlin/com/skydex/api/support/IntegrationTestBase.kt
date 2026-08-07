package com.skydex.api.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import com.skydex.api.security.TokenService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
abstract class IntegrationTestBase {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    internal lateinit var userRepository: UserRepository

    @Autowired
    internal lateinit var weatherEventRepository: WeatherEventRepository

    @Autowired
    internal lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    internal lateinit var tokenService: TokenService

    @BeforeEach
    fun clearDatabase() {
        weatherEventRepository.deleteAll()
        userRepository.deleteAll()
    }
}
