pluginManagement {

    repositories {
        maven { url = uri("https://repo.spring.io/milestone") }
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "demo1webfl"

include("modules:sqlite")
project(":modules:sqlite")
include("modules:sqlite-jdbc")
