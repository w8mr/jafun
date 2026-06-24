import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.JavadocJar

group = "nl.w8mr.jafun"
version = "0.0.1"


plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.publish)
}

val multiplatformId = libs.plugins.kotlinMultiplatform.get().pluginId
val publishId = libs.plugins.publish.get().pluginId

subprojects {
    repositories {
        mavenCentral()
    }

    apply(plugin = publishId)
    apply(plugin = multiplatformId)

    mavenPublishing {
        configure(KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = true,
            androidVariantsToPublish = emptyList<String>(),
        ))
        publishToMavenCentral()

        coordinates("nl.w8mr.jafun", "core", "0.0.1")

        pom {
            name.set("Jafun")
            description.set("A functional language for the JVM")
            inceptionYear.set("2024")
            url.set("https://github.com/w8mr/jafun")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("https://opensource.org/license/mit")
                }
            }
            issueManagement {
                system.set("Github")
                url.set("https://github.com/w8mr/jafun/issues")
            }

            developers {
                developer {
                    id.set("w8mr")
                    name.set("Elmar Wachtmeester")
                    url.set("https://github.com/w8mr")
                }
            }

            scm {
                url.set("https://github.com/w8mr/jafun/")
                connection.set("https://github.com/w8mr/jafun.git")
                developerConnection.set("scm:git:ssh://git@github.com:w8mr/jafun.git")
            }
        }
        signAllPublications()
    }
}