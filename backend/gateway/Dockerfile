# Both stages pin the same Ubuntu Noble generation of Eclipse Temurin 21 as the ledger's
# Dockerfile, verified with `docker manifest inspect` before this file was committed (CI's
# setup-java uses "21"; the development machine runs 21.0.9, an earlier update of the
# same line).

# --- Build -----------------------------------------------------------------------------------
# The wrapper and pom.xml are copied first so `dependency:go-offline` is cached across source
# changes; only editing pom.xml busts this layer.
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
# Tests are skipped here on purpose: CI runs the full suite (`make test-gateway`), and the image
# build has no reason to repeat it.
RUN ./mvnw -B package -DskipTests

# --- Runtime -----------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --system gateway && useradd --system --gid gateway --no-create-home gateway
WORKDIR /app
# The narrower glob matters: spring-boot-maven-plugin repackages the artifact in place and
# renames the pre-repackage jar to *.jar.original, and a bare *.jar would match both.
# The jar and /app stay root-owned and read-only to the service user, so code running in the JVM
# cannot replace what every restart runs, as the assistant leaves its .venv (ADR-054).
COPY --from=build /build/target/gateway-*.jar app.jar
USER gateway

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
