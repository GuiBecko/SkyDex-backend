# SkyDex — backend

Part of [SkyDex](https://github.com/GuiBecko/skydex): a gamified camera app for
capturing meteorological events and sharing them with friends. Start there for
the architecture and screenshots.

This is the REST API: authentication, captures, the weather-event dex, friends
and the feed. Kotlin, Spring Boot 3.2, Postgres.

## What it does

A capture is a photograph plus coordinates plus a claimed phenomenon. Before
anything is stored, the API checks the claim two ways:

1. **Is it outdoors?** The photograph goes to
   [skydex-vision](https://github.com/GuiBecko/SkyDex-vision), which scores how
   much it looks like an outdoor sky. Below `skydex.vision.outdoor-min` (0.60),
   the upload is refused with 422 and nothing is written.
2. **Does it match the weather?** The claimed phenomenon is checked against
   Open-Meteo's hourly record for those coordinates at that time, and against
   what the photograph itself looks like. A contradiction only blocks when the
   model is confident both ways — see the commentary in
   `src/main/resources/application.properties`, which explains which thresholds
   are measured and which are judgement.

`TravelPlausibility` additionally rejects a capture that would require moving
faster than a person can between two consecutive captures.

## Running it

The easiest path is the whole stack at once, from the umbrella repository:

    git clone https://github.com/GuiBecko/skydex && cd skydex
    cp .env.example .env
    docker compose up

To run only this service, you need a Postgres and a reachable skydex-vision:

    cp .env.example .env      # then edit it
    ./gradlew bootRun

It listens on `SERVER_PORT` (default 3002) and answers `GET /actuator/health`.

## Configuration

Every setting is an environment variable with a development default; see
`src/main/resources/application.properties`. The only one without a default is
`TOKEN_JWT_SECRET`, which must be set explicitly.

## Testing

    ./gradlew test

23 test classes. The integration tests start a real Postgres through
Testcontainers, so **Docker must be running**. There are no mocked repositories
in the integration layer — the queries are exercised against the real database.

## Licence

MIT — see `LICENSE`.
