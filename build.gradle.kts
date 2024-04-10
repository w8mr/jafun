plugins {
    kotlin("jvm") version "1.9.22"
    application
    `maven-publish`
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
}

group = "nl.w8mr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":parsek"))
    implementation(project(":kasmine"))
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Test> {
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
}

ktlint {
    version.set("1.2.1")
}
