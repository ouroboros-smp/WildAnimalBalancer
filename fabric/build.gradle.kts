plugins {
    id("net.fabricmc.fabric-loom") version "1.17.14"
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.154.2+26.2")
    implementation(include("me.lucko:fabric-permissions-api:0.7.0")!!)

    implementation(project(":core"))
    include(project(":core"))
    include("org.yaml:snakeyaml:2.5")

    testImplementation("net.fabricmc:fabric-loader-junit:0.19.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

base {
    archivesName.set("WildAnimalBalancer-fabric")
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "wildanimalbalancer-gametest"
        enableGameTests = true
        enableClientGameTests = false
        eula = true
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
