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
    // 저장소 파일을 읽는 테스트는 이 경로에서만 찾는다(RepoFiles). 작업 디렉터리에서 올라가면 worktree 가 main checkout 의 파일을 읽는다.
    systemProperty("repo.root", rootProject.rootDir.absolutePath)
    // 테스트가 읽는 저장소 파일을 입력으로 선언하지 않으면 그 파일만 바꿨을 때 테스트가 UP-TO-DATE 로 건너뛰어진다.
    // 비밀 파일(.env, *.pem)은 해시에도 넣지 않도록 읽는 종류만 고른다.
    inputs.files(
        rootProject.fileTree("deploy/vultr") { include("*.sh", "*.yml", "Caddyfile", "sql/**", ".env.example") },
        rootProject.file(".github/workflows/deploy.yml"),
    ).withPropertyName("repoFilesReadByTests").withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}
