package com.diajarkoding.imfit.data.remote.online

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Scripted boundary fake, not a second backend implementation or a production fallback. */
internal class FakeOnlineApi : OnlineTransport {
    class Request(val rpc: String, val body: JsonObject, val credential: RequestCredential) {
        private val response = CompletableDeferred<RpcResponse>()
        fun reply(body: String, status: Int = 200) { response.complete(RpcResponse(status, body)) }
        fun loseResponse() { response.completeExceptionally(IOException("synthetic response loss")) }
        suspend fun await() = response.await()
    }
    val requests = Channel<Request>(Channel.UNLIMITED)
    var ignoreCancellation = false
    override suspend fun post(name: String, body: JsonObject, credential: RequestCredential): RpcResponse {
        val request = Request(name, body, credential)
        requests.send(request)
        return if (ignoreCancellation) withContext(NonCancellable) { request.await() } else request.await()
    }
}

internal object OnlineFixtures {
    const val time = "2026-10-28T02:00:00.123456+00:00"
    val snapshot: String = """{
      "id":"session","version":2,"status":"PAUSED","effective_status":"PAUSED","review_required":false,
      "template_id":null,"template_name":"Routine","template_revision":2147483648,
      "started_at":"$time","paused_at":"$time","total_paused_ms":0,
      "last_activity_at":"$time","expires_at":"2026-11-27T02:00:00.123456Z","schema_version":1,
      "current_exercise_id":null,"completed_workout_id":null,"exercises":[{
        "id":"child","exercise_id":"squat","name":"Squat","order_index":0,"rest_seconds":60,
        "muscle_category":"Legs","max_set_number":5,"input_defaults":{"sets":3,"reps":10},
        "sets":[{"id":"set","set_number":5,"weight":"10.00","reps":10,"is_completed":true}]
      }]
    }"""
    fun receipt(request: String = "request", version: Long = 2, operation: String = "update_active_session", completed: Boolean = false): String {
        val snap = onlineJson.parseToJsonElement(snapshot).jsonObject.toMutableMap()
        snap["version"] = JsonPrimitive(version)
        val status = if (completed) "COMPLETED" else "PAUSED"
        snap["status"] = JsonPrimitive(status)
        snap["effective_status"] = JsonPrimitive(status)
        if (completed) {
            snap["completed_workout_id"] = JsonPrimitive("workout")
            snap["paused_at"] = JsonNull
        }
        return buildJsonObject {
            put("request_id", request); put("session_id", "session"); put("version", version)
            put("operation", operation); put("status", status); put("committed_at", time)
            put("snapshot", JsonObject(snap)); if (completed) put("workout_id", "workout")
        }.toString()
    }
    fun operation(result: String, request: String = "request", operation: String = "update_active_session") = """{
      "state":"COMMITTED","receipt":{"request_id":"$request","operation":"$operation",
      "resource_type":"SESSION","resource_id":"session","committed_at":"$time",
      "committed_at_source":"COMMIT","result":$result}}
    """
    const val emptyPage = """{"data":[],"page_info":{"next_cursor":null,"has_more":false,"limit":20}}"""
    val summary = """{"id":"workout","template_id":null,"template_name":"Routine",
      "start_time":"$time","end_time":"$time","duration_seconds":2147483648,
      "total_volume":"123456789.123456789","total_sets":1,"total_reps":10}"""
}
