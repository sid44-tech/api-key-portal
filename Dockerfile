# syntax=docker/dockerfile:1

FROM adoptopenjdk:11-jdk-hotspot AS build
WORKDIR /workspace

# Resolve dependencies in their own layer so source changes don't re-download them
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B package -DskipTests

FROM adoptopenjdk:11-jre-hotspot
WORKDIR /app

RUN groupadd --system portal && useradd --system --gid portal portal
COPY --from=build /workspace/target/api-key-portal-*.jar app.jar
RUN mkdir /app/data && chown portal:portal /app/data
USER portal

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
