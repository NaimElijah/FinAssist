# Both stages pin the same Ubuntu Noble generation of Eclipse Temurin 21, verified with
# `docker manifest inspect` before this file was committed (CI's setup-java uses "21"; the
# development machine runs 21.0.9).

# --- Build -----------------------------------------------------------------------------------
# The wrapper and pom.xml are copied first so `dependency:go-offline` is cached across source
# changes; only editing pom.xml busts this layer.
FROM eclipse-temurin:21.0.9_10-jdk-noble AS build
WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
# Tests are skipped here on purpose: CI runs the full suite (`make test-ledger`), including the
# Testcontainers-backed *IT tests, which need a Docker daemon the image build does not have.
RUN ./mvnw -B package -DskipTests

# --- Runtime -----------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.9_10-jre-noble
RUN groupadd --system ledger && useradd --system --gid ledger --no-create-home ledger
WORKDIR /app
# The narrower glob matters: spring-boot-maven-plugin repackages the artifact in place and
# renames the pre-repackage jar to *.jar.original, and a bare *.jar would match both.
COPY --from=build /build/target/ledger-*.jar app.jar
RUN chown ledger:ledger app.jar
USER ledger

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
