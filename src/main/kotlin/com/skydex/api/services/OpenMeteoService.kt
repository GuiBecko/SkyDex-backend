package com.skydex.api.services

import com.skydex.api.dto.EventoProximo
import com.skydex.api.dto.OpenMeteoResponse
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class OpenMeteoService {

    private val restClient = RestClient.create("https://api.open-meteo.com")

    fun buscarEventosProximos(lat: Double, lng: Double): List<EventoProximo?> {
        val resposta = try {
            restClient.get()
                .uri("/v1/forecast?latitude=$lat&longitude=$lng&hourly=temperature_2m,weather_code")
                .retrieve()
                .body(OpenMeteoResponse::class.java)
        } catch (e: Exception) {
            println("Erro Open-Meteo: ${e.message}")
            return emptyList()
        }

        if (resposta?.hourly == null) return emptyList()

        val eventosEncontrados = mutableListOf<EventoProximo>()

        // Vamos varrer apenas as próximas 24 horas (posições de 0 a 23 na lista)
        for (i in 0..23) {
            val codigo = resposta.hourly.weather_code[i]
            val hora = resposta.hourly.time[i]
            val temp = resposta.hourly.temperature_2m[i]

            if(codigo == null || temp == null) continue

            when (codigo) {
                0, 1, 2, 3 -> eventosEncontrados.add(EventoProximo("Céu Limpo / Nublado", hora, temp, "Tranquilo"))
                45, 48 -> eventosEncontrados.add(EventoProximo("Nevoeiro Intenso", hora, temp, "Interessante"))
                65 -> eventosEncontrados.add(EventoProximo("Chuva Forte", hora, temp, "Atenção"))
                71, 73, 75 -> eventosEncontrados.add(EventoProximo("Neve", hora, temp, "Interessante"))
                95 -> eventosEncontrados.add(EventoProximo("Tempestade com Trovões", hora, temp, "Perigo"))
                96, 99 -> eventosEncontrados.add(EventoProximo("Tempestade Severa com Granizo", hora, temp, "Perigo Extremo!"))
                // Se for 0 (Sol), 1, 2, etc., ele ignora e não adiciona à lista
            }
        }

        return eventosEncontrados
    }
}