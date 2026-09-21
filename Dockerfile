# ---- build stage ------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package -DskipTests

# ---- runtime stage ----------------------------------------------------------
# Distroless-style minimal JRE image: SRE tooling should have a tiny attack surface.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 autosreguard
USER autosreguard
WORKDIR /app
COPY --from=build /src/target/autosreguard-*.jar /app/autosreguard.jar
COPY config/autosreguard.properties /app/config/autosreguard.properties
EXPOSE 9464
ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "/app/autosreguard.jar", "--serve", "--config=/app/config/autosreguard.properties"]
