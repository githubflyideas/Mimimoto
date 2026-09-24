package mimimoto

import kotlin.system.exitProcess

/**
 * Entry point for `core`'s own suite.
 *
 * Separate from `:server`'s runner because it is a separate JVM with a
 * different classpath — that separation is the whole point. `:core` must
 * compile and pass with nothing on its classpath but the Kotlin standard
 * library, which is what makes it safe to drop into the Android app unchanged.
 * Running its tests from `:server`, where the server's classpath is present,
 * would quietly stop checking that.
 */
fun main() {
    fftTests()
    exitProcess(Suite.report())
}
