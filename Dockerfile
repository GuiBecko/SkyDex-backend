# Build with the Gradle wrapper the repository pins (9.6.0), so the image does
# not depend on whatever Gradle the host happens to have.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# Wrapper and build scripts first: this layer is cached until a dependency
# actually changes, which keeps an ordinary source edit off the download path.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
# -x test: the suite needs Docker for Testcontainers, which is not available
# inside a build. Tests run on the host, in CI or by hand -- never here.
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /srv

# Unprivileged: nothing in this service needs root, and it writes uploaded
# photographs to disk.
RUN useradd --system --uid 10001 skydex \
    && mkdir -p /srv/data/photos \
    && chown -R skydex:skydex /srv
USER skydex

COPY --from=build --chown=skydex:skydex /src/build/libs/*-SNAPSHOT.jar /srv/app.jar

# Matches server.port's default in application.properties.
EXPOSE 3002

# Actuator is already a dependency; /actuator/health is exposed by default.
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD ["sh", "-c", "curl -fsS http://localhost:${SERVER_PORT:-3002}/actuator/health || exit 1"]

ENTRYPOINT ["java", "-jar", "/srv/app.jar"]
