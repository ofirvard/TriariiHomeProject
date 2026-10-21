# ingest-service image. Build from the repository root (it needs the shared common module and the Gradle wrapper):
#   docker build -f ingest-service/Dockerfile -t ingest-service .

# --- build: compile the boot jar (tests run in CI, not here)
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon -q :ingest-service:bootJar -x test

# --- runtime: JRE only, non-root, with a health check
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/ingest-service/build/libs/app.jar app.jar
USER app

# All other configuration comes from environment variables at runtime.
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/health > /dev/null || exit 1

# Size the heap from the container's memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
