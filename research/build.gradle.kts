plugins {
    application
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1")
    }
}

application {
    mainClass.set("com.trading.research.cli.MainKt")
}

dependencies {
    implementation(project(":common"))

    // Kotlin / JSON / Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Database (sync JDBC — batch historical loads)
    implementation("org.postgresql:postgresql")
    implementation("com.zaxxer:HikariCP")

    // CLI
    implementation("com.github.ajalt.clikt:clikt:5.0.1")

    // Logging
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")

    // Tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}
