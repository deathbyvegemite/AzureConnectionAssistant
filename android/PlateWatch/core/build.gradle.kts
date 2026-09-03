plugins {
    alias(libs.plugins.kotlin.jvm)
}

/*
 * Deliberately a plain JVM module with no Android dependencies.
 *
 * Everything that is genuinely hard about plate reading — repairing OCR character
 * confusion, pooling frames into a single sighting, deduplicating a car you follow
 * for a kilometre — lives here, where it can be unit tested on a laptop in seconds
 * instead of on a phone in traffic.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
