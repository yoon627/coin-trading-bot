plugins {
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1")
    }
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // Spring Boot context (for @ConfigurationProperties on TradingProperties).
    // spring-boot-autoconfigure 는 common 코드에서 사용하지 않아 제거.
    implementation("org.springframework.boot:spring-boot")
}
