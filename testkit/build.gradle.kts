plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Nothing but the Kotlin standard library, same as :core. The runner has to
// build on a host that cannot reach Maven at all (see scripts/build.sh), so a
// dependency here would defeat the reason it exists instead of JUnit.
dependencies { }

kotlin { jvmToolchain(17) }
