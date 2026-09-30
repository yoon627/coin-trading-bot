plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
    jacoco
}

dependencies {
    implementation(project(":common"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-crypto")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // JWT for Upbit API auth
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Database
    runtimeOnly("io.r2dbc:r2dbc-h2")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.test {
    // 알림 메시지가 금액을 기본 로케일로 찍고(`DiscordNotifier` 의 "%,.0f원") 테스트가 그 문자열을 단언한다 — 그룹 구분자가
    // `,` 가 아닌 로케일에서는 깨진다. en·ko 에서는 고정 없이도 통과해 드러나지 않으므로 테스트 JVM 로케일을 고정한다.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    // 배포 스크립트·compose 를 읽는 테스트가 있다(TradingEnvPassthroughTest·ExitParamsPreflightScriptTest) —
    // 입력으로 선언하지 않으면 그 파일만 고쳤을 때 테스트가 UP-TO-DATE 로 건너뛰어진다.
    inputs.files(
        rootProject.file("deploy/vultr/deploy.sh"),
        rootProject.file("deploy/vultr/docker-compose.prod.yml"),
        rootProject.file("deploy/vultr/preflight_exit_params.sh"),
    ).withPropertyName("deployScripts").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}
