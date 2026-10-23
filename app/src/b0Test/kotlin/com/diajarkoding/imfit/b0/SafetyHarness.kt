package com.diajarkoding.imfit.b0

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Test-only rendezvous: timeout detects a broken test, never schedules a race by sleeping. */
class SuspendProbe {
    private val reached = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    suspend fun pause() {
        reached.complete(Unit)
        withTimeout(10_000) { released.await() }
    }
    suspend fun awaitReached() = withTimeout(10_000) { reached.await() }
    fun release() { released.complete(Unit) }
}

/** Used where Room invokes a non-suspending Executor/QueryCallback. */
class BlockingProbe {
    private val reached = CountDownLatch(1)
    private val released = CountDownLatch(1)
    fun pause() {
        reached.countDown()
        check(released.await(10, TimeUnit.SECONDS)) { "B0 probe release timed out" }
    }
    fun awaitReached() { check(reached.await(10, TimeUnit.SECONDS)) { "B0 probe not reached" } }
    fun release() = released.countDown()
}

class TransactionProbeExecutor : Executor, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val next = AtomicReference<BlockingProbe?>()
    private val probes = java.util.concurrent.CopyOnWriteArrayList<BlockingProbe>()
    fun arm(): BlockingProbe = BlockingProbe().also { check(next.compareAndSet(null, it)); probes += it }
    override fun execute(command: Runnable) {
        val probe = next.getAndSet(null)
        executor.execute { probe?.pause(); command.run() }
    }
    override fun close() {
        probes.forEach { it.release() }
        next.getAndSet(null)?.release()
        executor.shutdown()
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "B0 Room executor did not stop" }
    }
}

/** Not injected into production SystemClock calls: its limitations are explicit in the report. */
class ControlledClock(var monotonicMs: Long = 0, var wallMs: Long = 1_000) {
    fun advance(delta: Long) { require(delta >= 0); monotonicMs += delta; wallMs += delta }
}

enum class SessionEvent { LOGIN, LOGOUT, RESTORE, EXPIRY, DEEP_LINK }
data class SessionIdentity(val owner: String?, val epoch: Long, val event: SessionEvent)
class ControlledSession {
    var identity = SessionIdentity(null, 0, SessionEvent.RESTORE)
        private set
    fun transition(owner: String?, event: SessionEvent): SessionIdentity =
        SessionIdentity(owner, identity.epoch + 1, event).also { identity = it }
}

data class RequestRecord(val id: Int, val endpoint: String, val dataset: String, val epoch: Long?, var terminal: String = "RUNNING")
class RequestLedger {
    private val rows = mutableListOf<RequestRecord>()
    private var active = 0
    var maxConcurrency = 0
        private set
    var joined = 0
        private set
    @Synchronized fun start(endpoint: String, epoch: Long? = null): Int {
        active++
        maxConcurrency = maxOf(maxConcurrency, active)
        val dataset = when {
            endpoint.contains("/auth/") -> "AUTH"
            endpoint.contains("/exercises") -> "CATALOG"
            endpoint.contains("/profiles") -> "PROFILE"
            endpoint.contains("template") -> "TEMPLATES"
            endpoint.contains("workout") || endpoint.contains("exercise_logs") -> "HISTORY"
            else -> "OTHER"
        }
        return rows.size.also { rows += RequestRecord(it, endpoint, dataset, epoch) }
    }
    @Synchronized fun joined() { joined++ }
    @Synchronized fun finish(id: Int, terminal: String) {
        check(rows[id].terminal == "RUNNING")
        rows[id].terminal = terminal
        active--
    }
    @Synchronized fun snapshot() = rows.map { it.copy() }
    @Synchronized fun active() = active
    @Synchronized fun count(endpoint: String) = rows.count { it.endpoint == endpoint }
    @Synchronized fun clear() {
        check(active == 0)
        rows.clear(); maxConcurrency = 0; joined = 0
    }
}

/** All requests stay in MockEngine, even if a production adapter asks for an unexpected URL. */
class ScriptedSyncRemote {
    data class Reply(val body: String = "[]", val status: Int = 200)
    val ledger = RequestLedger()
    private val bodies = mutableListOf<Pair<String, String>>()
    var script: suspend (HttpRequestData) -> Reply = { Reply() }
    var currentEpoch: () -> Long? = { null }
    val engine = MockEngine { request ->
        check(request.url.host == "phase-b0.invalid") { "B0 refused non-fixture host" }
        val endpoint = "${request.method.value} ${request.url.encodedPath}"
        val id = ledger.start(endpoint, currentEpoch())
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        synchronized(bodies) { bodies += endpoint to body }
        try {
            val reply = script(request)
            ledger.finish(id, "HTTP_${reply.status}")
            respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf("Content-Type", "application/json"))
        } catch (e: CancellationException) {
            ledger.finish(id, "CANCELLED"); throw e
        } catch (e: Exception) {
            ledger.finish(id, "FAILED"); throw e
        }
    }
    fun capturedBodies(endpoint: String) = synchronized(bodies) { bodies.filter { it.first == endpoint }.map { it.second } }
}

enum class BaselineClassification { BASELINE_PASS, BASELINE_FAILURE, EXPECTED_UNSAFE_BEHAVIOR, NOT_TESTED, BLOCKED_ENVIRONMENT }
fun evidence(id: String, classification: BaselineClassification, facts: String) =
    "B0|$id|$classification|$facts".also(::println)
