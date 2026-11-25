// Adapters module: inbound/outbound adapters and the Spring Boot bootstrap.
// This is the only module that knows about Spring, SQL, and external services.

plugins {
    java
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
}

// Testcontainers 1.20.x (Boot 3.4.2's default) speaks Docker API 1.32, which Docker 29 refuses;
// 1.21.4 negotiates a supported version. Test scope only. See DECISIONS.md, "Cloud run setup".
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")      // RestClient + Jackson (web server disabled)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Resilience on every external call (retry + circuit breaker + rate limiter).
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    // Telegram long-polling bot.
    implementation("org.telegram:telegrambots:6.9.7.1")

    // Official Anthropic (Claude) SDK.
    implementation("com.anthropic:anthropic-java:2.34.0")

    // Jakarta WebSocket client for the Alpaca market-data stream.
    implementation("org.glassfish.tyrus.bundles:tyrus-standalone-client:2.1.5")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

// The worker's bootJar main class (the screen CLI has its own @SpringBootApplication).
springBoot {
    mainClass.set("io.github.signalbot.SignalBotApplication")
}

// One-off candidate screener: ./gradlew screen  (separate from the worker runtime)
tasks.register<JavaExec>("screen") {
    group = "application"
    description = "Run the one-off candidate screener and print a PASS/FAIL table"
    mainClass.set("io.github.signalbot.screen.ScreenApplication")
    classpath = sourceSets["main"].runtimeClasspath
}
