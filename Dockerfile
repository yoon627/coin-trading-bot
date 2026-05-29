# Builds the :bot module only (the committed app). When the collector/research
# modules are added, re-add their build.gradle.kts COPY lines for dep caching.
FROM gradle:8.12-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY common/build.gradle.kts ./common/
COPY bot/build.gradle.kts ./bot/
RUN gradle :bot:dependencies --no-daemon || true
COPY common ./common
COPY bot ./bot
RUN gradle :bot:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/bot/build/libs/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
