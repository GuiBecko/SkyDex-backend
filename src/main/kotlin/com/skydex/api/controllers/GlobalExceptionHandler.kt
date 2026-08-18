package com.skydex.api.controllers

import com.skydex.api.dto.ErrorResponse
import com.skydex.api.errors.BadRequestException
import com.skydex.api.errors.ConflictException
import com.skydex.api.errors.ForbiddenException
import com.skydex.api.errors.NotFoundException
import com.skydex.api.errors.ServiceUnavailableException
import com.skydex.api.errors.UnprocessableContentException
import com.skydex.api.services.BadUploadException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
// Aliased: Spring's marker interface for "an exception that already knows its own HTTP status"
// collides by simple name with this API's own `ErrorResponse` body DTO.
import org.springframework.web.ErrorResponse as SpringErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(e: NotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message ?: "Not found"))

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(e: ForbiddenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(e.message ?: "Forbidden"))

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(e: ConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(e.message ?: "Conflict"))

    @ExceptionHandler(BadUploadException::class)
    fun handleBadUpload(e: BadUploadException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(e.message ?: "Invalid upload"))

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(e: BadRequestException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message ?: "Bad request"))

    /**
     * Explicit, not left to the catch-all: `ServiceUnavailableException` does not implement
     * Spring's `ErrorResponse`, so `handleUnexpected` would report an upstream outage as a 500 —
     * telling the client we are broken when the honest answer is "try again shortly".
     */
    @ExceptionHandler(ServiceUnavailableException::class)
    fun handleServiceUnavailable(e: ServiceUnavailableException): ResponseEntity<ErrorResponse> {
        log.warn("An upstream this request needs is unavailable: {}", e.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse(e.message ?: "Service temporarily unavailable"))
    }

    @ExceptionHandler(UnprocessableContentException::class)
    fun handleUnprocessableContent(e: UnprocessableContentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(e.message ?: "Content could not be accepted"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
            .ifBlank { "Invalid request" }
        return ResponseEntity.badRequest().body(ErrorResponse(message))
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(e: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse("Photo is too large"))

    /**
     * A uniqueness invariant enforced by the database rather than by the code that checked first.
     *
     * Three sites do check-then-write against a unique constraint and none of them can do it
     * atomically: `AuthController.register` and `UserController.updateMe` against `users.email`,
     * and `FriendshipService.request` against `(requester_id, addressee_id)`. Their reads and their
     * writes are separate statements, so two registrations of the same address arriving together
     * both miss the check, and a double-tapped "Enviar convite" sends two identical inserts. The
     * constraint stops the loser — correctly — but it stops it by throwing, and without this
     * handler that throw is a 500 for what is plainly a conflict.
     *
     * The message is deliberately generic: the exception text carries the constraint name and the
     * offending value, which is diagnostic detail for the log, not for the client. In the ordinary
     * (non-racing) case the caller never gets here at all — the explicit checks at those three
     * sites produce their own specific messages first. This is the net under them.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        log.warn("A database constraint rejected a write", e)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("That conflicts with a record that already exists"))
    }

    /**
     * The malformed-request family, which the catch-all below would otherwise misreport as 500.
     *
     * These three are how a request can be wrong before it ever reaches a handler: a body that is
     * not valid JSON (or does not fit the DTO), a path variable or query parameter that will not
     * convert — `DELETE /api/events/not-a-uuid` — and a binding failure. Spring's
     * `DefaultHandlerExceptionResolver` answers all of them 400.
     *
     * They need naming explicitly because, unlike `NoResourceFoundException` and
     * `HttpRequestMethodNotSupportedException`, **they do not implement `ErrorResponse`**, so the
     * status-preserving branch of the catch-all cannot recognise them. That is not obvious from
     * reading the interface's javadoc, and it is exactly the sort of thing a catch-all quietly gets
     * wrong: adding one turned every malformed body in this API into a 500 until this handler was
     * added. Both cases are pinned in `GlobalExceptionHandlerTest`.
     *
     * `MethodArgumentNotValidException` also extends `BindException`, but its own handler above is
     * more specific and still wins — it is the one that reports which field failed and why.
     */
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        BindException::class
    )
    fun handleMalformedRequest(e: Exception): ResponseEntity<ErrorResponse> {
        log.debug("Rejecting a malformed request", e)
        return ResponseEntity.badRequest().body(ErrorResponse("Request could not be read"))
    }

    /**
     * The catch-all, so that *every* error this API emits is a `{"error": "..."}` envelope.
     *
     * Without it, anything not named above falls through to Spring's default `/error` body, which
     * has an entirely different shape — and every Android repository parses exactly one shape. A
     * client meeting the default body reports a parse failure instead of the actual problem.
     *
     * **The `ErrorResponse` branch is not optional.** `ExceptionHandlerExceptionResolver` runs
     * before `DefaultHandlerExceptionResolver`, so an advice claiming `Exception` also claims
     * Spring's own dispatch exceptions — `NoResourceFoundException` (404),
     * `HttpRequestMethodNotSupportedException` (405), `HttpMessageNotReadableException` (400) and
     * the rest. Those already know their correct status, and answering them with 500 would both
     * lie to the client and drown genuine faults in a bucket full of mistyped URLs. Spring marks
     * every one of them with the `ErrorResponse` interface, so they can be recognised as a group
     * and re-emitted with their own status in our envelope. `GlobalExceptionHandlerTest` and
     * `WeatherEventControllerTest` pin both halves.
     *
     * Everything else is a real fault: logged at ERROR **with the stack trace**, because a 500
     * whose cause is not in the log is a 500 nobody can fix, and answered with a fixed message
     * that reveals nothing about the internals.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        if (e is SpringErrorResponse) {
            val status = e.statusCode
            log.debug("Request rejected by Spring MVC with status {}", status, e)
            return ResponseEntity.status(status)
                .body(ErrorResponse(e.body.detail ?: e.message ?: "Request could not be processed"))
        }

        log.error("Unhandled exception; answering 500", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("Something went wrong. Please try again."))
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
