package com.skydex.api.service

import com.skydex.api.services.OpenMeteoClient
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives [OpenMeteoClient] against a throwaway loopback HTTP server, so the timeout behaviour can
 * be exercised without reaching the real Open-Meteo.
 *
 * A plain unit test: the client takes its base URL as a constructor argument, so no Spring context
 * and no database are involved.
 */
class OpenMeteoClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** Released in teardown so a deliberately stalled handler cannot outlive its test. */
    private val release = CountDownLatch(1)

    /**
     * The query string of the last request `respondWith`'s handler received. Populated
     * unconditionally on every call, but only asserted on by the test that needs to pin what the
     * client actually requested — the other tests here only care about the response.
     */
    private var capturedQuery: String? = null

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        baseUrl = "http://${server.address.hostString}:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        release.countDown()
        server.stop(0)
    }

    private fun respondWith(body: String) {
        server.createContext("/") { exchange ->
            capturedQuery = exchange.requestURI.query
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    /** Accepts the connection, then never answers — the shape of a wedged upstream. */
    private fun neverRespond() {
        server.createContext("/") { exchange ->
            release.await(60, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
    }

    /**
     * The failure this exists to prevent is not a slow capture — it is the whole API going down.
     *
     * `RestClient.create(...)` inherits the JDK default of **no read timeout**. Every call to this
     * client runs on a Tomcat request thread, so an upstream that accepts connections and then
     * stops answering parks one thread per capture attempt, permanently, until the pool is gone
     * and every endpoint stops responding — including the ones that never touch Open-Meteo.
     *
     * `CaptureCommitService` already reasoned carefully about not holding a database connection
     * across this call, which mitigated the second-order effect while leaving the first-order one
     * wide open.
     *
     * Without a read timeout this test does not fail quickly — it hangs, and the preemptive
     * deadline is what turns that into a reportable failure.
     */
    @Test
    fun `an upstream that never answers gives up instead of parking the thread forever`() {
        neverRespond()
        val client = OpenMeteoClient(baseUrl)

        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            // Degrades to null, the same as any other upstream failure: the caller scores the
            // capture UNCONFIRMED rather than failing the request.
            assertNull(client.fetchHourlyForecast(-30.0346, -51.2177))
        }
    }

    /**
     * The premise guard. A client pointed at the wrong place, or one whose timeouts were set so
     * tight that nothing ever completes, would satisfy the test above by returning null every
     * time. This pins that a normal response still arrives and still parses.
     */
    @Test
    fun `a normal response is parsed into the forecast`() {
        respondWith(
            """
            {
              "latitude": -30.0346,
              "longitude": -51.2177,
              "hourly": {
                "time": ["2026-08-09T17:00"],
                "temperature_2m": [19.0],
                "weather_code": [95]
              }
            }
            """.trimIndent()
        )
        val client = OpenMeteoClient(baseUrl)

        val forecast = client.fetchHourlyForecast(-30.0346, -51.2177)

        assertEquals(-30.0346, forecast?.latitude)
        assertEquals(listOf(95), forecast?.hourly?.weatherCode)
        assertEquals(listOf(19.0), forecast?.hourly?.temperatureCelsius)
    }

    /**
     * Night is the one condition under which the photo check is skipped entirely, so the capture
     * path cannot work without this series. Asking for it costs nothing: the call to Open-Meteo is
     * already being made for the weather code.
     */
    @Test
    fun `requests the is_day series and parses it`() {
        respondWith(
            """
            {
              "latitude": -30.0,
              "longitude": -51.0,
              "hourly": {
                "time": ["2026-08-16T14:00"],
                "temperature_2m": [21.0],
                "weather_code": [95],
                "is_day": [1]
              }
            }
            """.trimIndent()
        )
        val client = OpenMeteoClient(baseUrl)

        val response = client.fetchHourlyForecast(-30.0, -51.0)

        // Pins the request half of this test's name: without this, the assertion below would
        // pass even if the client stopped asking Open-Meteo for is_day at all, because this
        // throwaway server returns whatever body the test hands it regardless of what was
        // requested. Dropping is_day from the query silently re-enables the night photo check,
        // since a missing series is deliberately read as daylight — so this must fail loudly.
        assertEquals(true, capturedQuery?.contains("is_day"))
        assertEquals(listOf(1), response?.hourly?.isDay)
    }

    /**
     * A cached or proxied response from before this field was requested. Missing daylight
     * information must not fail the parse — the caller defaults to treating it as day.
     */
    @Test
    fun `tolerates a response with no is_day series`() {
        respondWith(
            """
            {
              "latitude": -30.0,
              "longitude": -51.0,
              "hourly": {
                "time": ["2026-08-16T14:00"],
                "temperature_2m": [21.0],
                "weather_code": [95]
              }
            }
            """.trimIndent()
        )
        val client = OpenMeteoClient(baseUrl)

        assertEquals(emptyList<Int?>(), client.fetchHourlyForecast(-30.0, -51.0)?.hourly?.isDay)
    }
}
