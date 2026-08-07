# Backend Overview

This is the backend for the SkyDex Application, built with SpringBoot and Kotlin

## Useful commands

- `JAVA_HOME=$HOME/.jdks/ms-17.0.20 ./gradlew test` -> Running Tests (needs Docker; uses Testcontainers, never touches the dev DB)
- `JAVA_HOME=$HOME/.jdks/ms-17.0.20 ./gradlew test --tests "com.skydex.api.controller.AuthControllerTest"` -> Running one test class
- `docker start skydex-db && JAVA_HOME=$HOME/.jdks/ms-17.0.20 ./gradlew bootRun` -> Running Server on :8080


