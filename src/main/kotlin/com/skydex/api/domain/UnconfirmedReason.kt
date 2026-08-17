package com.skydex.api.domain

/**
 * Why a capture was stored as [ValidationStatus.UNCONFIRMED].
 *
 * Three values, and the one that is missing is worth naming. Before the vision model, the commonest
 * cause was "the phenomenon you claimed is not the one Open-Meteo recorded" — and that cause can no
 * longer occur, because the user no longer makes a claim. An upstream failure or a capture outside
 * the forecast window, which used to land in the same bucket, are now a 503 instead: without
 * Open-Meteo there is no phenomenon, and `weather_events.phenomenon` is NOT NULL.
 *
 * Null on a row means the reason was not recorded — either it predates this enum, or the capture is
 * CONFIRMED. Both read correctly as "nothing to explain".
 */
enum class UnconfirmedReason {
    /** The photograph confidently shows something the recorded weather rules out. */
    PHOTO_CONTRADICTS_WEATHER,

    /** The position is not one the author could have reached since their previous capture. */
    IMPLAUSIBLE_TRAVEL,

    /** The client reported the fix as coming from a mock location provider. */
    MOCK_LOCATION
}
