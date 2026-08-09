package com.skydex.api.domain

/**
 * Whether Open-Meteo's record for the capture's place and time agrees with what the user
 * claimed to have photographed. UNCONFIRMED is not an accusation — an upstream outage or a
 * capture outside the forecast window lands here too. It only means: no XP awarded.
 */
enum class ValidationStatus { CONFIRMED, UNCONFIRMED }
