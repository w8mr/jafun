rootProject.name = "jafun"
include("core")

includeBuild("/Users/elmar/Development/Kotlin/kasmine") {
    dependencySubstitution {
        substitute(module("nl.w8mr.kasmine:core"))
            .using(project(":core"))
    }
}
