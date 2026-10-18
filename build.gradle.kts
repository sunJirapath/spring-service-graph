plugins {
    application
}

group = "io.github.sunjirapath"
// release builds pass -PreleaseVersion=1.2.3 (from the git tag)
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("com.github.javaparser:javaparser-core:3.28.2")
    implementation("org.yaml:snakeyaml:2.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")

    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.github.sunjirapath.ssg.Main"
    applicationName = "ssg"
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    // regenerate golden files with: ./gradlew test -PupdateGolden
    systemProperty("ssg.updateGolden", project.hasProperty("updateGolden").toString())
}
