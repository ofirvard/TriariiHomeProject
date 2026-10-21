# alert-service runtime image. It packages the jar that Gradle built and CI tested, so nothing is compiled here
# and an unchanged jar means every layer is a cache hit:
#   ./gradlew :alert-service:bootJar && docker build -t alert-service alert-service
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY build/libs/app.jar app.jar
USER app

# All other configuration comes from environment variables at runtime.
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/health > /dev/null || exit 1

# Size the heap from the container's memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
