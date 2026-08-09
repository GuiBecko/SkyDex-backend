package com.skydex.api.dto

import com.skydex.api.domain.Phenomenon

data class NearbyPhenomenonResponse(
    val phenomenon: String,
    val phenomenonName: String,
    val rarity: String,
    val time: String,
    val temperatureCelsius: Double?,
    val alertLevel: String
) {
    companion object {
        fun from(phenomenon: Phenomenon, time: String, temperatureCelsius: Double?) =
            NearbyPhenomenonResponse(
                phenomenon = phenomenon.name,
                phenomenonName = phenomenon.displayName,
                rarity = phenomenon.rarity.name,
                time = time,
                temperatureCelsius = temperatureCelsius,
                alertLevel = phenomenon.alertLevel
            )
    }
}
