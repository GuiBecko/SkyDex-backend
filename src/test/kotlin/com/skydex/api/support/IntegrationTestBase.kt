package com.skydex.api.support

import com.fasterxml.jackson.databind.ObjectMapper
import com.skydex.api.repositories.EventoRepository
import com.skydex.api.repositories.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
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
    protected lateinit var userRepository: UserRepository

    @Autowired
    protected lateinit var eventoRepository: EventoRepository

    @BeforeEach
    fun clearDatabase() {
        eventoRepository.deleteAll()
        userRepository.deleteAll()
    }
}
