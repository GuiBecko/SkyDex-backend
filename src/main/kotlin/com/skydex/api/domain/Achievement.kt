package com.skydex.api.domain

/**
 * Everything an achievement rule is allowed to look at. Passing one immutable snapshot keeps
 * the rules pure and testable — they never reach into a repository.
 */
data class AchievementContext(
    val confirmedCaptures: Int,
    val unconfirmedCaptures: Int,
    val distinctSpecies: Int,
    val totalSpecies: Int,
    val speciesCounts: Map<Phenomenon, Int>,
    val friends: Int
)

/**
 * The badge shelf. Each entry owns its own unlock rule, so adding a badge is one line here
 * and nothing else — BadgeService iterates the catalog and never knows what the rules are.
 *
 * Names and descriptions are user-facing pt-BR copy, per the Global Constraints.
 */
enum class Achievement(
    val displayName: String,
    val description: String,
    private val rule: (AchievementContext) -> Boolean
) {
    FIRST_CAPTURE(
        "Molhou o Dedo",
        "Você apontou a câmera pro céu e deu certo. Uma vez.",
        { it.confirmedCaptures >= 1 }
    ),
    THREE_CAPTURES(
        "Caçador de Nuvem",
        "Três registros confirmados. Já dá pra puxar assunto no elevador.",
        { it.confirmedCaptures >= 3 }
    ),
    TEN_CAPTURES(
        "Meteorologista de Varanda",
        "Dez confirmados. Os vizinhos já perguntam se vai chover.",
        { it.confirmedCaptures >= 10 }
    ),
    FIFTY_CAPTURES(
        "Homem do Tempo",
        "Cinquenta confirmados. A emissora local que se cuide.",
        { it.confirmedCaptures >= 50 }
    ),
    FIVE_SPECIES(
        "Colecionador de Céu",
        "Cinco espécies diferentes no SkyDex. Tá ficando sério.",
        { it.distinctSpecies >= 5 }
    ),
    ALL_SPECIES(
        "Doutor Tempestade",
        "Todas as espécies capturadas. O céu não tem mais segredos pra você.",
        { it.totalSpecies > 0 && it.distinctSpecies >= it.totalSpecies }
    ),
    THUNDER_CHASER(
        "Pé de Raio",
        "Você ficou do lado de fora durante uma tempestade. Por uma foto.",
        { (it.speciesCounts[Phenomenon.THUNDERSTORM] ?: 0) >= 1 }
    ),
    HAIL_SURVIVOR(
        "Sobrevivente do Granizo",
        "Granizo confirmado. Esperamos que o carro esteja bem.",
        { (it.speciesCounts[Phenomenon.HAILSTORM] ?: 0) >= 1 }
    ),
    SNOW_SEEKER(
        "Viu Neve de Verdade",
        "Neve confirmada. Isso aqui não acontece todo dia.",
        { (it.speciesCounts[Phenomenon.SNOW] ?: 0) >= 1 }
    ),
    FOG_WALKER(
        "Perdido na Neblina",
        "Nevoeiro registrado. Presumimos que você achou o caminho de volta.",
        { (it.speciesCounts[Phenomenon.FOG] ?: 0) >= 1 }
    ),
    OBVIOUS_PHOTOGRAPHER(
        "Fotógrafo do Óbvio",
        "Você registrou um céu limpo e o SkyDex confirmou. Corajoso.",
        { (it.speciesCounts[Phenomenon.CLEAR_SKY] ?: 0) >= 1 }
    ),
    WEATHER_OPTIMIST(
        "Otimista Climático",
        "Cinco palpites que o Open-Meteo não confirmou. A esperança é a última que morre.",
        { it.unconfirmedCaptures >= 5 }
    ),
    WEATHER_NETWORK(
        "Rede de Estações",
        "Três amigos no SkyDex. Agora é uma operação.",
        { it.friends >= 3 }
    );

    fun isEarnedBy(context: AchievementContext): Boolean = rule(context)
}
