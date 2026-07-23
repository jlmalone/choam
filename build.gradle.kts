plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "vision.salient"
version = providers.fileContents(layout.projectDirectory.file("VERSION")).asText.get().trim()

repositories {
    mavenCentral()
}

dependencies {
    // Sietch core (file indexing + hashing via composite build)
    implementation("vision.salient.sietch:sietch-core")

    // CLI Framework
    implementation("com.github.ajalt.clikt:clikt:4.2.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Network (client)
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")

    // Web dashboard (server)
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-html-builder:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-server-cors:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.ktor:ktor-server-test-host:3.0.3")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

application {
    mainClass.set("vision.salient.choam.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // Fork a fresh JVM per test class to avoid OOM with 300+ tests creating temp SQLite DBs
    forkEvery = 50
}

kotlin {
    jvmToolchain(21)
}
