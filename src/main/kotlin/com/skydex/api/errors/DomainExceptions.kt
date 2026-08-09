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
