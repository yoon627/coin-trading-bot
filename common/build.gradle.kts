plugins {
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

dependencyManagement {
    imports {
        // common 은 Boot 플러그인을 쓰지 않으므로 루트에 선언한 버전의 BOM 을 직접 가져온다.
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // @ConfigurationProperties 용
    implementation("org.springframework.boot:spring-boot")
}
