plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.yaml:snakeyaml:2.5")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The explicit fabric.mod.json keeps this module's jar-in-jar mod id unique: Loom's generated
// id is group + project name, and every Ouroboros mod shares the com.ouroboros group — a bare
// 'core' collides with e.g. coffer's nested core module, and Fabric Loader keeps only one
// winner per id, evicting the other mod's classes at boot.
tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

sourceSets.test {
    resources.srcDir(rootProject.file("paper/src/main/resources"))
}

tasks.processTestResources {
    from(rootProject.file("fabric/src/main/resources/config.yml")) {
        rename { "fabric-config.yml" }
    }
}

tasks.jar {
    archiveBaseName.set("WildAnimalBalancer-core")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
