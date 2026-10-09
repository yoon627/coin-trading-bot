plugins {
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

dependencyManagement {
    imports {
        // common 은 Boot 플러그인을 적용하지 않는다 — 루트에 선언한 플러그인 버전의 BOM 을 그대로 따른다.
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
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
