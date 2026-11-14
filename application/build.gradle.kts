// Application module: use cases and policies (risk limits, trigger engine).
// Depends on the domain; still framework-free so it stays unit-testable.

dependencies {
    "implementation"(project(":domain"))

    "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
