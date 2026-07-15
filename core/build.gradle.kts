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
