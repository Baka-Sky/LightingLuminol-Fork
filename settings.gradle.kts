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

rootProject.name = "lightingluminol"

include("lightingluminol-api")
include("lightingluminol-server")

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val lightingluminolVersionChannel = providers.gradleProperty("channel").get().trim()
    val lightingluminolBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (lightingluminolBuildNumber == null) {
        "$mcVersion.0-R0.1-SNAPSHOT"
    } else {
        "$mcVersion.0-R0.1-build.$lightingluminolBuildNumber-${lightingluminolVersionChannel.lowercase()}"
    }
    version = versionString
}