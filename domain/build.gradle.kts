// Domain module: pure Java 21, records and port interfaces only.
// No framework, no I/O, no third-party dependencies are allowed here.

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
