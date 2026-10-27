plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
    jacoco
}

dependencies {
    implementation(project(":common"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-crypto")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Upbit API 인증용 JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    runtimeOnly("io.r2dbc:r2dbc-h2")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.test {
    // 알림 금액을 기본 로케일로 포맷하고 테스트가 "," 구분자를 단언하므로 로케일을 고정한다.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    // 배포 스크립트를 읽는 테스트가 있어, 입력으로 선언하지 않으면 스크립트만 바꿨을 때 테스트가 UP-TO-DATE 로 건너뛰어진다.
    inputs.files(
        rootProject.file("deploy/vultr/deploy.sh"),
        rootProject.file("deploy/vultr/docker-compose.prod.yml"),
        rootProject.file("deploy/vultr/preflight_exit_params.sh"),
        rootProject.file("deploy/vultr/deploy_needed.sh"),
        rootProject.file(".github/workflows/deploy.yml"),
        rootProject.file("deploy/vultr/notify_deploy.sh"),
    ).withPropertyName("deployScripts").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}
