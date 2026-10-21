# One image definition for both services:
#   docker build --build-arg SERVICE=ingest-service .
#   docker build --build-arg SERVICE=alert-service .

# --- build: compile the service's boot jar (tests run in CI, not here)
FROM eclipse-temurin:17-jdk-alpine AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew --no-daemon -q ":${SERVICE}:bootJar" -x test \
    && cp "${SERVICE}/build/libs/app.jar" /app.jar

# --- runtime: JRE only, non-root, with a health check
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app

# Both services listen on 8080 inside the container; all other config comes from env vars at runtime.
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/health > /dev/null || exit 1

# Size the heap from the container's memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
