# 모듈을 추가하면 그 build.gradle.kts 도 아래 의존성 캐시용 COPY 에 넣는다.
# jar 는 플랫폼과 무관하므로 빌드 단계를 빌더 플랫폼에 고정해 QEMU 에뮬레이션을 피한다.
FROM --platform=$BUILDPLATFORM gradle:8.12-jdk21 AS build
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
RUN apk add --no-cache curl && adduser -D -u 1001 app
WORKDIR /app
COPY --from=build /app/bot/build/libs/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
USER app
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
