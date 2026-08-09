package com.skydex.api.domain

/**
 * How much the server was able to check about a capture.
 *
 * CONFIRMED means exactly three things, and it is worth reading them as the complete list:
 * - Open-Meteo's record for that place and time agrees with the phenomenon the user claimed;
 * - the capture cites a photo this same user uploaded minutes earlier and has not spent before;
 * - the position is one the user could have reached from their previous capture, and the client
 *   did not report the fix as coming from a mock provider.
 *
 * What CONFIRMED still does NOT mean is that the user was physically there. Every input above is
 * either a public fact anyone can look up (the weather) or an assertion by the client (the
 * coordinates, the mock flag) — and the photo, though genuinely the caller's own upload, is only
 * ever proof that they uploaded a file. Closing that last gap needs device attestation, which this
 * server does not have and cannot fake. Do not build anything that treats CONFIRMED as presence.
 *
 * UNCONFIRMED is not an accusation either. An upstream outage, a capture outside the forecast
 * window, or a legitimate user whose previous coordinates were wrong all land here too. It only
 * means: no XP awarded.
 */
enum class ValidationStatus { CONFIRMED, UNCONFIRMED }
