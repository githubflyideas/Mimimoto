plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    testImplementation(project(":testkit"))
}

kotlin { jvmToolchain(17) }

application { mainClass.set("mimimoto.cli.DaemonKt") }

sourceSets["test"].compileClasspath += sourceSets["main"].output
sourceSets["test"].runtimeClasspath += sourceSets["main"].output

tasks.register<JavaExec>("qc") {
    group = "application"
    description = "Run the audio quality gate over WAV files"
    mainClass.set("mimimoto.cli.QcKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (project.hasProperty("args")) args((project.property("args") as String).split(" "))
}

// The suite runs through the in-repo runner rather than JUnit, so the same
// tests execute on a host with no Maven access (see scripts/build.sh).
tasks.register<JavaExec>("suite") {
    group = "verification"
    description = "Run the test suite"
    mainClass.set("mimimoto.AllTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") { dependsOn("suite") }
