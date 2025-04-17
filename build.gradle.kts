
plugins {
    kotlin("multiplatform") version "2.1.10"
    `maven-publish`
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
}

group = "nl.w8mr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvm {
        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
    js {
        browser()
        nodejs()
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("nl.w8mr.parsek:core:0.1.1")
                implementation("nl.w8mr.kasmine:core:0.0.4")
                implementation(kotlin("reflect"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

ktlint {
    version.set("1.2.1")
}
