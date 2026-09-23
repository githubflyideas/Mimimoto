/**
 * Runs the API and the sample-expiry loop.
 *
 * Scoped to the stage this project is actually at: enough to run a pilot
 * against real families with real recordings, and no more. State is in memory
 * and audio is on local disk, which is the honest choice while the storage
 * question is still open (docs/DECISIONS.md, 未决).
 */
package mimimoto.cli

import mimimoto.httpapi.Api
import mimimoto.httpapi.BlobStore
import mimimoto.httpapi.buildServer
import mimimoto.store.MemoryStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("mimimoto")

fun main(args: Array<String>) {
    var port = 8080
    var blobDir = "./data/blobs"

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { i++; port = args[i].toInt() }
            "--blobs" -> { i++; blobDir = args[i] }
            "--help", "-h" -> {
                println("usage: mimimotod [--port 8080] [--blobs ./data/blobs]")
                return
            }
        }
        i++
    }

    val store = MemoryStore()
    val blobs = FileBlobStore(Path.of(blobDir))
    val api = Api(store, blobs)
    val server = buildServer(api, port)

    // Daily notes are short-lived by design, and retention that only happens
    // when someone remembers to run it is not retention.
    val expiry = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sample-expiry").apply { isDaemon = true }
    }
    expiry.scheduleAtFixedRate({
        try {
            val n = store.expireSamples(Instant.now())
            if (n > 0) log.info("expired $n sample(s)")
        } catch (e: Exception) {
            log.warning("expiry failed: ${e.message}")
        }
    }, 1, 1, TimeUnit.HOURS)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("shutting down")
        server.stop(5)
        expiry.shutdownNow()
    })

    server.start()
    log.info("listening on port $port, blobs in $blobDir")
    Thread.currentThread().join()
}

/**
 * Stores audio under a local directory.
 *
 * Local disk for the pilot. Whatever replaces it has to answer the storage
 * question properly — per-family keys, short retention on raw audio — and that
 * answer should come from a decision, not from whichever object store was
 * convenient on the day.
 */
class FileBlobStore(private val root: Path) : BlobStore {
    init {
        Files.createDirectories(root)
    }

    override fun put(key: String, data: ByteArray): String {
        // Keys are built from identifiers we generate, but normalising and
        // checking containment costs nothing and removes the question entirely.
        val target = root.resolve(key).normalize()
        require(target.startsWith(root.normalize())) { "unsafe blob key: $key" }

        Files.createDirectories(target.parent)
        // Write to a temp file and move, so a crash mid-write never leaves a
        // truncated sample that would later be cloned from.
        val tmp = Files.createTempFile(target.parent, ".tmp-", "")
        try {
            Files.write(tmp, data)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
        return target.toUri().toString()
    }
}
