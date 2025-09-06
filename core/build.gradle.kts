repositories {
    mavenCentral()
    mavenLocal()
}

group = parent?.group ?: group
version = parent?.version ?: version

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
                implementation("nl.w8mr.parsek:core:0.2.8")
                implementation("nl.w8mr.kasmine:core:0.0.5")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                implementation(kotlin("reflect"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
