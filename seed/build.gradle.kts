// seed -- the dataset generator.
//
// A SEPARATE MODULE, AND NOT A TEST FIXTURE.
//
//   PUB-7 requires the dataset to be code rather than a committed file. That requirement
//   is only as strong as the thing producing it, so the generator is a module a reader can
//   run and read, not a helper buried in the application's test sources where it would be
//   both harder to find and easier to point at a real database.
//
//   It deliberately does not depend on Spring. The generator's contract is: same seed
//   value in, same bytes out. A dependency on an application context would put a
//   configuration file between the seed value and that guarantee.

plugins {
    kotlin("jvm")
    application
}

val javaToolchainVersion: String by project
val junitVersion: String by project

kotlin {
    jvmToolchain(javaToolchainVersion.toInt())
}

application {
    mainClass = "net.gseek.proxima.seed.MainKt"
}

dependencies {
    // The driver is a compile dependency here, not runtimeOnly: the loader calls
    // PostgreSQL's CopyManager directly. Loading three million rows by INSERT is the
    // difference between minutes and tens of minutes, and this module exists to not do
    // that -- see docs/explanation/domain-model.md.
    implementation("org.postgresql:postgresql:42.7.11")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
