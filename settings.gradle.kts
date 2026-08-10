// Plugin versions resolve from gradle.properties so that every version in this build
// appears exactly once. See ADR-000 (Spring Boot line) and ADR-001 (QueryDSL on Kotlin).
//
// This block is first because Gradle requires it to be -- not by preference.
pluginManagement {
    val kotlinVersion: String by settings
    val springBootVersion: String by settings
    val springDependencyManagementVersion: String by settings

    plugins {
        kotlin("jvm") version kotlinVersion
        kotlin("kapt") version kotlinVersion
        kotlin("plugin.spring") version kotlinVersion
        kotlin("plugin.jpa") version kotlinVersion
        id("org.springframework.boot") version springBootVersion
        id("io.spring.dependency-management") version springDependencyManagementVersion
    }

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "proxima"

// api  -- the Spring Boot application and its Flyway migrations.
// seed -- the dataset generator. Separate because PUB-7 requires the data to be code, and
//         a generator living inside the application's test sources would be tempting to
//         run against a real database.
include("api", "seed")
