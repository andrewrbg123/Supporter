// Lets Gradle download the Java 25 toolchain when the build machine does not have one.
//
// Without this, building on a machine with only a newer JDK fails with
// "Cannot find a Java installation ... matching {languageVersion=25}". We keep the toolchain
// pinned to 25 rather than relaxing it, because the server runs JDK 25 (yolks:java_25) and the
// point of the pin is that the bytecode is built against exactly what runs it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "SupporterMod"
