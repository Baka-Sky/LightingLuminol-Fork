import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    id("moe.luminolmc.hyacinthusweight.patcher") version "+" // Automatically pulls the latest
}

paperweight {
    upstreams.register("luminol") {
        repo = github("LuminolMC", "Luminol")
        ref = providers.gradleProperty("luminolRef")

        patchFile {
            path = "luminol-server/build.gradle.kts"
            outputFile = file("lightingluminol-server/build.gradle.kts")
            patchFile = file("lightingluminol-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "luminol-api/build.gradle.kts"
            outputFile = file("lightingluminol-api/build.gradle.kts")
            patchFile = file("lightingluminol-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("lightingluminol-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchRepo("foliaApi") {
            upstreamPath = "folia-api"
            patchesDir = file("lightingluminol-api/folia-patches")
            outputDir = file("folia-api")
        }
        patchDir("luminolApi") {
            upstreamPath = "luminol-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("lightingluminol-api/luminol-patches")
            outputDir = file("luminol-api")
        }
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"
val menthaMavenPublicUrl = "https://repo.menthamc.com/repository/maven-public/"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven(menthaMavenPublicUrl)
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 21
        options.isFork = true
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }
/*
    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.menthamc.com/repository/maven-snapshots/") {
                name = "MenthaMC"
                credentials(PasswordCredentials::class) {
                    username = System.getenv("MAVEN_REPO_USER")
                    password = System.getenv("MAVEN_REPO_PASSWORD")
                }
            }
        }
    }
*/
     tasks.withType<Javadoc>().configureEach {
        options {
            (this as StandardJavadocDocletOptions).apply {
                addStringOption("-add-modules", "jdk.incubator.vector")
                addStringOption("Xdoclint:none", "-quiet")
            }
        }
    }
}
