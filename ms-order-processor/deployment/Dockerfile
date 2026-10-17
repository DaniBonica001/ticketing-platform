# syntax=docker/dockerfile:1
# Build context: ms-order-processor/   ->   docker build -f deployment/Dockerfile -t ms-order-processor .

# ---- Build stage -----------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk@sha256:40cefbf1de9d958594a1a38caafdd87e09bb8935b72ae3688ffe578f4f92767a AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --version --no-daemon
COPY domain ./domain
COPY infrastructure ./infrastructure
COPY applications ./applications
# Tests (including the Testcontainers suite) run in CI / `./gradlew build`; the image only packages the jar.
RUN ./gradlew :app-service:bootJar --no-daemon -x test -x pitest \
    && cp applications/app-service/build/libs/MsOrderProcessor.jar /workspace/app.jar

# ---- Runtime stage ---------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre@sha256:79605dbd43984ded68dd0628983e46f773a47b08d4b70a4f270be7034b4062b5
# wget is only used by the docker-compose healthcheck (/actuator/health/liveness).
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 appuser \
    && useradd --system --uid 10001 --gid appuser --no-create-home --shell /usr/sbin/nologin appuser
WORKDIR /app
COPY --from=build --chown=appuser:appuser /workspace/app.jar /app/app.jar
USER 10001:10001
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"
# Actuator only (health, info, prometheus). There is no public HTTP API.
EXPOSE 9090
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:9090/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
