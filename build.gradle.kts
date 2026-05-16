/*
 * Storra Minecraft plugin — build config.
 *
 * Targets Paper 1.21+ on Java 21. shadowJar bundles GSON +
 * sqlite-jdbc into the final artifact so merchants drop one
 * JAR into `plugins/` and don't have to manage transitive
 * dependencies.
 */

plugins {
    java
    // gradleup is the maintained fork; johnrengelman 8.1.1 ships
    // ASM 9.4 which doesn't read Java 21 bytecode (class file
    // major version 65). 8.3+ pulls ASM 9.7+.
    id("com.gradleup.shadow") version "8.3.5"
}

group = "xyz.storra"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    // Compile-time only — Paper provides this at runtime.
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    // Bundled at runtime via shadowJar.
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")

    // Tests.
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("com.github.tomakehurst:wiremock-jre8:3.0.1")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// Expand ${version} in plugin.yml at build time so the manifest
// reports the actual Gradle project version. Without this, Paper
// shows the literal "${version}" string to the dashboard (and the
// version-check cron treats every server as outdated). Scoped to
// plugin.yml to avoid touching binary resources.
tasks.processResources {
    val versionString = project.version.toString()
    inputs.property("version", versionString)
    filesMatching("plugin.yml") {
        expand("version" to versionString)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.shadowJar {
    archiveBaseName.set("storra-plugin")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    // Relocate bundled deps so they don't collide with anything
    // a server admin's other plugins might pull in.
    relocate("com.google.gson", "xyz.storra.plugin.shaded.gson")
    relocate("org.sqlite", "xyz.storra.plugin.shaded.sqlite")

    // Drop META-INF leftovers from shaded jars so the final
    // artifact stays minimal.
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/maven/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
