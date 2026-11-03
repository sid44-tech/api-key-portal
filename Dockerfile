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
# Healthy means the readiness probe says so: database reachable, encryption key
# working. bash and /dev/tcp rather than curl, which this image does not carry.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 \
        && printf "GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 \
        && head -n 1 <&3 | grep -q " 200 "' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
