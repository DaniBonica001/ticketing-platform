# Build context: ms-ticketing-api/
#   docker build -f deployment/Dockerfile -t ms-ticketing-api .

# Base images are pinned by the digest of their multi-arch index (tag kept for readability). To update:
#   docker buildx imagetools inspect eclipse-temurin:<tag>

# ---- Build stage -----------------------------------------------------------
FROM eclipse-temurin:25.0.4_7-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2 AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY domain ./domain
COPY infrastructure ./infrastructure
COPY applications ./applications
# Tests and the coverage gate run in CI (./gradlew build); the image only packages the application.
RUN ./gradlew --no-daemon :app-service:bootJar -x test \
    && cp applications/app-service/build/libs/MsTicketingApi.jar /workspace/app.jar

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:25.0.4_7-jre-alpine@sha256:2ca9adf44f5c29d28ecd26cf92d75cc0c66b7f32bfd839a4439e363a8b428af8
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER 10001:10001
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
# 8080: API. 9090: actuator (health, info, prometheus), internal network only.
EXPOSE 8080 9090
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:9090/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
