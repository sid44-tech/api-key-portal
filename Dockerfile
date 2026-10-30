# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so source changes don't re-download them
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system portal && useradd --system --gid portal portal
COPY --from=build /workspace/target/api-key-portal-*.jar app.jar
RUN mkdir /app/data && chown portal:portal /app/data
USER portal

# PORTAL_ENCRYPTION_KEY and PORTAL_PASSWORD_HASH must be provided at runtime,
# e.g. docker run --env-file .env. .env is never copied into the image.

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
