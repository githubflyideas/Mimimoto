/**
 * Judges recordings against the voice-cloning quality gate.
 *
 * This exists to be useful before the product does. The first real step for
 * this project is a manual pilot — generate stories for a handful of families
 * by hand and watch what happens — and the fastest way to waste that pilot is
 * to clone from a bad reference and conclude the model is not good enough.
 *
 *   qc --profile enrolment dad-enrolment.wav
 *   qc --json *.wav | jq -r 'select(.passed|not) | .file'
 *
 * Exit status is non-zero if any file fails, so it drops into a pre-flight
 * script unchanged.
 */
package mimimoto.cli

import mimimoto.audioqc.Profile
import mimimoto.audioqc.Report
import mimimoto.audioqc.analyse
import mimimoto.json.jsonOf
import mimimoto.json.render
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

/**
 * Forces UTF-8 on the console streams.
 *
 * Since JDK 18 `file.encoding` defaults to UTF-8, but `stdout.encoding` still
 * follows the console, which on a bare container is often ASCII. That turns
 * every non-ASCII character into '?' — and this tool prints transcripts and
 * findings for families recording in Chinese, Japanese, Thai and Vietnamese, so
 * a mangled console is not cosmetic.
 */
private fun forceUtf8Console() {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8))
}

fun main(args: Array<String>) {
    forceUtf8Console()
    var profile = Profile.DAILY
    var asJson = false
    var quiet = false
    val files = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--profile", "-p" -> {
                i++
                profile = when (args.getOrNull(i)?.lowercase()) {
                    "enrolment", "enrollment" -> Profile.ENROLMENT
                    "daily" -> Profile.DAILY
                    else -> {
                        System.err.println("unknown profile: ${args.getOrNull(i)}")
                        exitProcess(2)
                    }
                }
            }
            "--json" -> asJson = true
            "--quiet", "-q" -> quiet = true
            "--help", "-h" -> { usage(); exitProcess(0) }
            else -> {
                if (a.startsWith("-")) {
                    System.err.println("unknown flag: $a")
                    exitProcess(2)
                }
                files += a
            }
        }
        i++
    }

    if (files.isEmpty()) { usage(); exitProcess(2) }

    var failed = 0
    for (name in files) {
        val report = try {
            File(name).inputStream().use { analyse(it, profile) }
        } catch (e: Exception) {
            failed++
            if (asJson) {
                println(jsonOf("file" to name, "error" to (e.message ?: "unreadable")).render())
            } else {
                println("%-40s ERROR  %s".format(name, e.message))
            }
            continue
        }

        if (!report.passed) failed++
        when {
            asJson -> println(jsonLine(name, report))
            report.passed && quiet -> {}
            else -> printHuman(name, report)
        }
    }

    if (failed > 0) {
        System.err.println()
        System.err.println("$failed of ${files.size} file(s) not usable as a voice reference")
        exitProcess(1)
    }
}

private fun usage() {
    System.err.println(
        """
        usage: qc [flags] file.wav...

        flags:
          -p, --profile <enrolment|daily>  quality profile (default: daily)
              --json                       one JSON object per file
          -q, --quiet                      print only failures
        """.trimIndent()
    )
}

private fun jsonLine(name: String, r: Report) = jsonOf(
    "file" to name,
    "passed" to r.passed,
    "duration_s" to r.durationSeconds,
    "sample_rate" to r.sampleRate,
    "snr_db" to r.snrDb,
    "cutoff_hz" to r.cutoffHz,
    "speech_s" to r.speechSeconds,
    "clipping_ratio" to r.clippingRatio,
    "failures" to r.failures.map { it.code.wire },
    "warnings" to r.warnings.map { it.code.wire },
).render()

private fun printHuman(name: String, r: Report) {
    println("%-40s %s".format(name, if (r.passed) "PASS" else "FAIL"))
    println(
        "  %d Hz / %d-bit / %d ch, %.1fs (%.1fs speech, %.0f%%)".format(
            r.sampleRate, r.bitDepth, r.channels, r.durationSeconds, r.speechSeconds, r.speechRatio * 100
        )
    )
    println(
        "  SNR %.1f dB   cutoff %.0f Hz   clipping %.2f%%".format(
            r.snrDb, r.cutoffHz, r.clippingRatio * 100
        )
    )
    for (f in r.failures) println("  ✗ $f")
    for (w in r.warnings) println("  ! $w")
    println()
}
