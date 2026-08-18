package com.skydex.api.errors

class NotFoundException(message: String) : RuntimeException(message)

class ForbiddenException(message: String) : RuntimeException(message)

class ConflictException(message: String) : RuntimeException(message)

/**
 * Maps to 400. `BadUploadException` (in `services/PhotoStorageService.kt`) also maps to 400 —
 * that one means "this photo cannot be accepted" and stays scoped to the photo-upload path.
 * This one is for domain-level bad requests elsewhere (e.g. friend requests). Two 400-mapped
 * exceptions coexisting is the intended end state for the MVP, not an oversight to unify.
 */
class BadRequestException(message: String) : RuntimeException(message)

/**
 * Maps to **503**. An upstream this request cannot proceed without did not answer.
 *
 * Two callers, both on the capture path: `PhotoAnalysisService` when `skydex-vision` is
 * unreachable, and `CaptureValidationService` when Open-Meteo is. Both are genuinely retryable and
 * both cost the caller nothing — no photo is spent and no row is written before either can be
 * raised — so the client can simply try again with the same photo.
 *
 * Distinct from a 500 on purpose: 500 means we are broken, 503 means come back in a moment. The
 * Android client shows different copy for each, and telling a user to retry a genuine fault is
 * worse than useless.
 */
class ServiceUnavailableException(message: String) : RuntimeException(message)

/**
 * Maps to **422**. The request is well-formed and the caller is entitled to make it, but the
 * content itself cannot be accepted.
 *
 * One caller: a photo the vision model does not believe is the sky. This is deliberately not a 400
 * — a 400 in this API means "this request is malformed", and the Android client's error presenter
 * reads 400 as "re-check what you typed". Nothing was typed wrong here; the picture is the problem.
 */
class UnprocessableContentException(message: String) : RuntimeException(message)
