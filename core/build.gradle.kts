plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No dependencies at all, and nothing from the JVM desktop profile.
//
// That is what lets the Android app compile this module unchanged, which in
// turn is what makes the quality gate give the SAME verdict on the handset as
// on the server. A parent told "record again" by the app and then accepted by
// the server has caught us contradicting ourselves; one implementation makes
// that impossible rather than merely unlikely.
dependencies {
    // Test-only, so it never reaches a server artifact or the APK.
    testImplementation(project(":testkit"))
}

// `core` tests its own internals — the FFT among them — because Kotlin grants
// friend access only within a module. A test in :server cannot see them, and
// widening core's API so that it could would make an implementation detail
// something we owe compatibility on.
tasks.register<JavaExec>("suite") {
    group = "verification"
    description = "Run core's own test suite"
    mainClass.set("mimimoto.CoreTestsKt")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") { dependsOn("suite") }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Keep this module honest: anything reaching for java.desktop, or for a
        // JDK API Android lacks, should fail here rather than at the app's
        // first build.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
