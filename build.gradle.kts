plugins {
    id("org.springframework.boot") version "3.4.13" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.spring") version "2.1.0" apply false
}

allprojects {
    group = "com.trading"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.set(listOf("-Xjsr305=strict"))
        }
        // 기본(Gradle 데몬 상속 512m)으로는 bot 테스트 소스 컴파일이 CI 에서 힙 부족으로 죽는다.
        // gradle.properties 는 gitignore 라 여기서 정한다 — 로컬·CI·Docker 빌드가 같은 값을 쓴다.
        kotlinDaemonJvmArguments.set(listOf("-Xmx1536m"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
