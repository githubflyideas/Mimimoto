package mimimoto

import kotlin.system.exitProcess

fun main() {
    jsonTests()
    scheduleTests()
    audioQcTests()
    pipelineTests()
    storeTests()
    apiTests()
    exitProcess(Suite.report())
}
