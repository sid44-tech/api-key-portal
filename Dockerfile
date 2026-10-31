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

# PORTAL_ENCRYPTION_KEY must be provided at runtime, e.g. docker run --env-file .env
# (optionally with PORTAL_ADMIN_EMAIL / PORTAL_ADMIN_PASSWORD_HASH to seed the first
# account). .env is never copied into the image.

# Keep the SQLite database on the /app/data volume rather than the container layer
ENV PORTAL_DB_PATH=/app/data/keyportal.db

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
