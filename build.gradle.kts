// Root build. Declares the plugin set without applying it -- each module applies what it
// needs, and there is no `subprojects { }` block on purpose.
//
// The reason is that this repository is meant to be read. A module's build file should say
// what that module is, in one place, without the reader having to hold a root-level
// convention block in their head at the same time. There are two modules; the duplication
// that buys is a few lines.

plugins {
    kotlin("jvm") apply false
    kotlin("kapt") apply false
    kotlin("plugin.spring") apply false
    kotlin("plugin.jpa") apply false
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management") apply false
}

// Versions are resolved in settings.gradle.kts from gradle.properties.
