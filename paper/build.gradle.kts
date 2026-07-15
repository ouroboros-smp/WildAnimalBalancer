plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val coreJar = project(":core").tasks.named<Jar>("jar")

tasks.jar {
    archiveBaseName.set("WildAnimalBalancer-paper")
    dependsOn(coreJar)
    from({ zipTree(coreJar.get().archiveFile.get().asFile) }) {
        exclude("META-INF/MANIFEST.MF")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.runServer {
    minecraftVersion("1.21.11")
}
