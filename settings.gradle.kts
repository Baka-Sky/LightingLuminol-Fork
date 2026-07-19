pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "folia"

include("folia-api")
include("folia-server")

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val foliaVersionChannel = providers.gradleProperty("channel").get().trim()
    val foliaBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (foliaBuildNumber == null) {
        "$mcVersion.0-SNAPSHOT"
    } else {
        "$mcVersion.build.$foliaBuildNumber-${foliaVersionChannel.lowercase()}"
    }
    version = versionString
}