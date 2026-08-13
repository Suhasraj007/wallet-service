# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Cache dependencies separately from source for fast rebuilds
COPY pom.xml .
RUN mvn -q -e dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

# ---------- runtime stage ----------
FROM eclipse-temurin:17-jre-jammy
# curl is only for the container HEALTHCHECK
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app appuser
WORKDIR /app
COPY --from=build /app/target/wallet-service.jar app.jar
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Hosts such as Render inject their own PORT; the probe follows it.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT:-8080}/healthz" || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
