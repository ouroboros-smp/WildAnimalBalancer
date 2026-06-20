plugins {
    java
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "com.ouroboros"
version = "1.0.0"

java {
    // Paper 1.20.5+ / 1.21 requires Java 21.
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // We run on 1.21.11. The Folia schedulers used here are part of paper-api, so this
    // single dependency is enough and the jar runs on Paper and Folia across 1.21.x.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // --- Test-only dependencies (do not affect the shipped jar) ---
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveBaseName.set("WildAnimalBalancer")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Local integration: ./gradlew runServer downloads a Paper server and runs the plugin.
// CI uses direct server downloads (see .github/workflows/integration.yml); this is for local use.
tasks.runServer {
    minecraftVersion("1.21.11")
}