package com.skydex.api.domain

/**
 * How much the server was able to check about a capture.
 *
 * CONFIRMED means exactly four things, and it is worth reading them as the complete list:
 * - Open-Meteo has a record for that place and time, and it is the phenomenon the capture is
 *   stored under — the user no longer claims one, so this is a lookup rather than a check;
 * - the photograph does not confidently contradict that weather, and `skydex-vision` believed it
 *   was an outdoor sky when it was uploaded;
 * - the capture cites a photo this same user uploaded minutes earlier and has not spent before;
 * - the position is one the user could have reached from their previous capture, and the client
 *   did not report the fix as coming from a mock provider.
 *
 * What CONFIRMED still does NOT mean is that the user was physically there. The weather is a public
 * fact anyone can look up; the coordinates and the mock flag are assertions by the client; and the
 * photograph, though genuinely the caller's own upload and genuinely consistent with the sky, is
 * only ever proof that somebody photographed a sky like that one. Closing that last gap needs
 * device attestation, which this server does not have and cannot fake. Do not build anything that
 * treats CONFIRMED as presence.
 *
 * UNCONFIRMED is not an accusation either. It means no XP was awarded, and
 * [UnconfirmedReason] says which of three things happened. An unreachable upstream is no longer
 * one of them: without Open-Meteo there is no phenomenon at all, so that case is a 503 and no row
 * is written.
 */
enum class ValidationStatus { CONFIRMED, UNCONFIRMED }
