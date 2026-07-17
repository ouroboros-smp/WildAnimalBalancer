import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
}

allprojects {
    group = "com.ouroboros"
    version = "2.0.2"
}

val coverageReport = tasks.register("coverageReport") {
    group = "verification"
    description = "Generates JaCoCo coverage reports for every Java module."
}

subprojects {
    pluginManager.withPlugin("java") {
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> {
            toolVersion = "0.8.15"
        }

        tasks.withType<Test>().configureEach {
            finalizedBy(tasks.named("jacocoTestReport"))
        }
        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.withType<Test>())
            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }
        coverageReport.configure {
            dependsOn(tasks.named("jacocoTestReport"))
        }

        if (project.name == "core") {
            tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
                dependsOn(tasks.named("test"))
                violationRules {
                    rule {
                        limit {
                            counter = "LINE"
                            value = "COVEREDRATIO"
                            minimum = "0.85".toBigDecimal()
                        }
                        limit {
                            counter = "BRANCH"
                            value = "COVEREDRATIO"
                            minimum = "0.80".toBigDecimal()
                        }
                    }
                }
            }
            tasks.named("check") {
                dependsOn(tasks.named("jacocoTestCoverageVerification"))
            }
        }
    }
}
