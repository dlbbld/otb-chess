# OTB Chess — portability/VPS artifact. The iMac runtime stays native launchd; this image is the
# "run it anywhere" path. Builds the runnable jar (jar + target/lib alongside it, per the
# maven-jar-plugin manifest classpath) and serves it on a small JRE base.
#
#   docker build -t otb-chess .
#   docker run --rm -p 8080:8080 -p 8081:8081 otb-chess
#
# Behind a reverse proxy / single origin, publish only what the proxy needs and front it with Caddy
# (see Caddyfile) the same way the iMac does.

# --- build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Resolve dependencies in their own layer so source-only changes don't re-download them.
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

# --- runtime stage ---
FROM eclipse-temurin:17-jre
# curl is used by the container HEALTHCHECK below.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/otb-chess.jar ./otb-chess.jar
COPY --from=build /build/target/lib ./lib
COPY static ./static

# In a container the network boundary is the container itself, so bind all interfaces and control
# exposure via -p / an external proxy (unlike the iMac default of 127.0.0.1).
ENV OTB_BIND_HOST=0.0.0.0 \
    OTB_HTTP_PORT=8080 \
    OTB_WS_PORT=8081

EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -fsS "http://localhost:${OTB_HTTP_PORT}/api/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/otb-chess.jar"]
