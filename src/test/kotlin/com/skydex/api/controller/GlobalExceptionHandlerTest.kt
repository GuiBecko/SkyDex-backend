package com.skydex.api.controller

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.skydex.api.controllers.GlobalExceptionHandler
import com.skydex.api.dto.RegisterRequest
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.slf4j.LoggerFactory
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Every error this API can emit has to arrive in the `{"error": "..."}` envelope, because that is
 * the only shape the Android repositories know how to read. `GlobalExceptionHandler` covered six
 * exception types; anything else fell through to Spring's default `/error` body, which no client
 * can parse.
 *
 * These tests cover the two additions that close that gap, and — just as importantly — the cases
 * the catch-all must NOT swallow.
 */
class GlobalExceptionHandlerTest : IntegrationTestBase() {

    /**
     * Spies, not mocks: every method runs for real unless a test stubs it. They exist so a test can
     * produce a failure that is otherwise a genuine race or a genuine bug, neither of which can be
     * scheduled on demand.
     */
    @SpyBean
    private lateinit var usersSpy: UserRepository

    @SpyBean
    private lateinit var eventsSpy: WeatherEventRepository

    private val handlerLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
    private val logged = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun captureHandlerLog() {
        logged.start()
        handlerLogger.addAppender(logged)
    }

    @AfterEach
    fun releaseHandlerLog() {
        handlerLogger.detachAppender(logged)
        logged.stop()
    }

    /**
     * `AuthController.register` checks for the email and then inserts, which is not atomic. Two
     * registrations of the same address landing together both miss the check and both insert; the
     * unique constraint on `users.email` stops the second, by throwing. Same shape at
     * `UserController.updateMe` (unique email) and `FriendshipService.request` (unique
     * `(requester_id, addressee_id)`, i.e. a double-tapped "Enviar convite").
     *
     * Stubbing the lookup to miss is how that race is made deterministic: it reproduces exactly the
     * state the losing request is in — the row exists, its own read did not see it — without
     * needing two threads to interleave on cue.
     *
     * Untranslated, this is a 500 for what is really a conflict.
     */
    @Test
    fun `a uniqueness race that beats the check-then-write returns 409 in the error envelope`() {
        persistUser(email = "duplicate@skydex.com")
        doReturn(null).`when`(usersSpy).findByEmail("duplicate@skydex.com")

        val payload = RegisterRequest(
            name = "Second Arrival",
            email = "duplicate@skydex.com",
            password = "a-good-password"
        )

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").exists())
    }

    /**
     * The catch-all. Anything genuinely unhandled must still reach the client as a readable
     * envelope, and must reach the operator as a stack trace — a 500 whose cause is not in the log
     * is a 500 nobody can fix.
     */
    @Test
    fun `an unhandled exception returns 500 in the error envelope and logs the stack trace`() {
        val user = persistUser(email = "unhandled@skydex.com")
        doThrow(IllegalStateException("synthetic failure from the test"))
            .`when`(eventsSpy).findByUserIdOrderByCapturedAtDesc(user.id!!)

        mockMvc.perform(
            get("/api/events/mine").header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error").exists())

        val error = logged.list.firstOrNull { it.level == Level.ERROR }
        assertNotNull(error, "an unhandled exception must be logged at ERROR")
        assertNotNull(error!!.throwableProxy, "the log entry must carry the stack trace, not just a message")
        assertTrue(
            generateSequence(error.throwableProxy) { it.cause }
                .any { it.message?.contains("synthetic failure from the test") == true },
            "the logged throwable should be the one that was actually raised"
        )
    }

    /**
     * The trap the catch-all sets for itself, and the reason it cannot simply be
     * `@ExceptionHandler(Exception::class) -> 500`.
     *
     * `ExceptionHandlerExceptionResolver` runs *before* `DefaultHandlerExceptionResolver`, so an
     * advice that claims `Exception` also claims Spring's own dispatch exceptions — no such route,
     * method not allowed, unreadable body. Those are ordinary 4xx answers about the request, and
     * reporting them as 500 would both mislead the client and bury real faults in the same bucket
     * as typos in a URL.
     */
    @Test
    fun `an unknown route is still a 404, not a 500`() {
        val user = persistUser(email = "wrong-turn@skydex.com")

        mockMvc.perform(
            get("/api/there-is-no-such-endpoint").header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isNotFound)
    }

    /**
     * The same guard for a malformed body: a client mistake, not a server fault.
     *
     * This one is sharper than it looks. `HttpMessageNotReadableException` does **not** implement
     * `ErrorResponse`, unlike the routing exceptions above, so the status-preserving branch of the
     * catch-all does not catch it and it needs a handler of its own. Adding the catch-all without
     * one turned every malformed body in this API into a 500 — this test is what caught that.
     */
    @Test
    fun `an unreadable request body is still a 400, not a 500`() {
        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ this is not json")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").exists())
    }

    /**
     * And for a path variable that will not convert. `MethodArgumentTypeMismatchException` is the
     * other member of that family that does not implement `ErrorResponse`, and every `{id}` route
     * in this API — captures, friend requests — takes a `UUID`, so a client sending a non-UUID hits
     * it. It is a 400 about the request, not a fault.
     */
    @Test
    fun `an unparseable id in the path is still a 400, not a 500`() {
        val user = persistUser(email = "bad-uuid@skydex.com")

        mockMvc.perform(
            delete("/api/events/definitely-not-a-uuid").header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").exists())
    }
}
