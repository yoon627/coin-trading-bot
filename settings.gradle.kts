plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "coin-trading-bot"
include("common", "collector", "bot")
