plugins {
    java
}

group = "com.peoplesserver"
version = "0.1.0"

java {
    toolchain {
        // The People's Server runs JDK 25.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    // TODO(phase-0): add the Hytale server API repository here once FactionMod's
    // build.gradle confirms whether the server API is published to a maven repo
    // or consumed as a local flatDir jar.
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("com.google.code.gson:gson:2.14.0")

    // TODO(phase-0): compileOnly(files("libs/hytale-server.jar")) — the server jar is
    // provided at runtime by the platform and must NOT be bundled into the plugin jar.

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Minimal fat-jar. sqlite-jdbc and gson are bundled because the server almost certainly
// does not provide them. If FactionMod's build.gradle shows the server already exposes
// either library, drop it from here to avoid a duplicate-class conflict on the classpath.
tasks.register<Jar>("pluginJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
}

tasks.build {
    dependsOn("pluginJar")
}
