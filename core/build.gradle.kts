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
dependencies { }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Keep this module honest: anything reaching for java.desktop, or for a
        // JDK API Android lacks, should fail here rather than at the app's
        // first build.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}
