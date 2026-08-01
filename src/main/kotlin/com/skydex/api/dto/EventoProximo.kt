package com.skydex.api.dto

data class EventoProximo(
    val fenomeno: String,
    val horario: String,
    val temperatura: Double?,
    val nivelAlerta: String
)
