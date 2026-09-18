/**
 * A tiny test runner.
 *
 * In-repo rather than JUnit so the suite compiles and runs with nothing but
 * kotlinc — which matters on any build host that cannot reach Maven. The
 * assertion names match kotlin.test, so moving to JUnit later is deleting this
 * file and adding a dependency, not rewriting the tests.
 */
package mimimoto

class Assertion(message: String) : AssertionError(message)

object Suite {
    private data class Result(val name: String, val failure: String?)

    private val results = mutableListOf<Result>()
    private var group = ""

    fun group(name: String, body: () -> Unit) {
        group = name
        body()
        group = ""
    }

    fun test(name: String, body: () -> Unit) {
        val label = if (group.isEmpty()) name else "$group / $name"
        results += try {
            body()
            Result(label, null)
        } catch (e: Assertion) {
            Result(label, e.message ?: "assertion failed")
        } catch (e: Throwable) {
            Result(label, "${e::class.simpleName}: ${e.message}")
        }
    }

    /** Prints the report and returns a process exit code. */
    fun report(): Int {
        val failed = results.filter { it.failure != null }
        for (r in results) {
            if (r.failure == null) {
                println("  ok    ${r.name}")
            } else {
                println("  FAIL  ${r.name}")
                println("        ${r.failure}")
            }
        }
        println()
        println("${results.size - failed.size}/${results.size} passed")
        return if (failed.isEmpty()) 0 else 1
    }
}

fun fail(message: String): Nothing = throw Assertion(message)

fun assertTrue(condition: Boolean, message: String = "expected true") {
    if (!condition) fail(message)
}

fun assertFalse(condition: Boolean, message: String = "expected false") {
    if (condition) fail(message)
}

fun <T> assertEquals(expected: T, actual: T, message: String = "") {
    if (expected != actual) {
        fail(if (message.isEmpty()) "expected <$expected>, got <$actual>"
        else "$message: expected <$expected>, got <$actual>")
    }
}

fun assertNear(expected: Double, actual: Double, tolerance: Double, message: String = "") {
    if (kotlin.math.abs(expected - actual) > tolerance) {
        fail("${if (message.isEmpty()) "" else "$message: "}expected $expected ± $tolerance, got $actual")
    }
}

fun assertNotNull(value: Any?, message: String = "expected non-null") {
    if (value == null) fail(message)
}

fun assertNull(value: Any?, message: String = "expected null") {
    if (value != null) fail("$message (got $value)")
}

/** Asserts that [body] throws [T], and returns the exception for inspection. */
inline fun <reified T : Throwable> assertThrows(message: String = "", body: () -> Unit): T {
    try {
        body()
    } catch (e: Throwable) {
        if (e is T) return e
        fail("expected ${T::class.simpleName}, got ${e::class.simpleName}: ${e.message}")
    }
    fail(if (message.isEmpty()) "expected ${T::class.simpleName} to be thrown" else message)
}
