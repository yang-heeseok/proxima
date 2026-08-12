// api -- the Spring Boot application and its Flyway migrations.

plugins {
    kotlin("jvm")
    kotlin("kapt")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val javaToolchainVersion: String by project
val querydslVersion: String by project
val archunitVersion: String by project

kotlin {
    jvmToolchain(javaToolchainVersion.toInt())
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // QueryDSL -- the community fork, via kapt with the jakarta classifier. ADR-001.
    // Both this and the original com.querydsl artefact were built and run against
    // PostgreSQL before choosing; the fork won on maintenance, not capability.
    implementation("io.github.openfeign.querydsl:querydsl-jpa:$querydslVersion")
    kapt("io.github.openfeign.querydsl:querydsl-apt:$querydslVersion:jakarta")

    // The pool's own metrics. T1 is a report about connection pool exhaustion, and a
    // report about a pool that cannot quote the pool's gauges is a report about a guess.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The T3 regression gate. These rules are structural: they read bytecode and fail when
    // a shape returns that made @Transactional inert, which is a class of defect no runtime
    // test can catch cheaply -- the runtime test needs a container, a schema, and a
    // deliberately failing unit of work to see it.
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // WHY THE TEST CONFIGURATION IS A PROFILE AND NOT `application.yml`.
    //
    //   It used to be src/test/resources/application.yml, which SHADOWS the main one
    //   rather than adding to it: both are `classpath:/application.yml` and Spring loads
    //   the first match, which is the test resource. So the test suite was configuring the
    //   application from a file that mentioned nothing but the datasource, and every other
    //   setting fell back to its framework default.
    //
    //   That meant the tests had never once exercised the configuration that ships. It was
    //   found by a T1 regression gate asserting that no OpenEntityManagerInViewInterceptor
    //   is registered: the gate failed against a main config that sets open-in-view=false,
    //   because the main config was not being read.
    //
    //   A profile-specific file is ADDITIVE. application.yml loads, application-test.yml
    //   layers the test-only datasource over it.
    systemProperty("spring.profiles.active", "test")
}
