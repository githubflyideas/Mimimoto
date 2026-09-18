plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

// No runtime dependencies beyond the Kotlin standard library.
//
// Everything this service needs is already in the JDK: java.time for the
// timezone work, javax.sound.sampled for WAV decoding, java.net.http for
// calling the synthesis worker, com.sun.net.httpserver for serving. JSON is a
// few hundred lines in mimimoto.json rather than a dependency — the wire
// surface here is small enough that the trade favours having no version to
// track. If that stops being true, swapping in kotlinx-serialization is a
// change to one package.
//
// The tests deliberately use the in-repo runner (mimimoto.testkit) rather than
// JUnit, so that `scripts/build.sh` can compile and run the whole suite with
// nothing but kotlinc — useful on build hosts with no Maven access.
dependencies {
    implementation(kotlin("stdlib"))
}

kotlin { jvmToolchain(21) }

application { mainClass.set("mimimoto.cli.DaemonKt") }

tasks.register<JavaExec>("qc") {
    group = "application"
    description = "Run the audio quality gate over WAV files"
    mainClass.set("mimimoto.cli.QcKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (project.hasProperty("args")) args((project.property("args") as String).split(" "))
}

tasks.register<JavaExec>("suite") {
    group = "verification"
    description = "Run the test suite through the in-repo runner"
    mainClass.set("mimimoto.AllTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

sourceSets["test"].compileClasspath += sourceSets["main"].output
sourceSets["test"].runtimeClasspath += sourceSets["main"].output
