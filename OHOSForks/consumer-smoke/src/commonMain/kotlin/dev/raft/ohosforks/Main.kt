package dev.raft.ohosforks

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val completed = atomic(0)

private suspend fun exercisePublishedGraph() = coroutineScope {
    val launched = launch {
        completed.incrementAndGet()
    }
    val answer = async {
        completed.incrementAndGet()
        42
    }

    launched.join()
    check(answer.await() == 42)
    check(completed.value == 2)
}

fun main() = runBlocking {
    exercisePublishedGraph()
}
