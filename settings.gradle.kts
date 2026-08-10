rootProject.name = "proxima"

// api  -- the Spring Boot application and its Flyway migrations.
// seed -- the dataset generator. Separate because PUB-7 requires the data to be code, and
//         a generator living inside the application's test sources would be tempting to
//         run against a real database.
include("api", "seed")

// NOTE: no build.gradle.kts yet. Dependency versions are deliberately not committed until
// ADR-000 (Spring Boot line) and ADR-001 (QueryDSL on Kotlin) are decided against the
// current release list rather than from memory. A build file written first would make
// those decisions silently.
