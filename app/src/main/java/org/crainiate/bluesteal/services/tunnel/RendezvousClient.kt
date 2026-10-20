package org.crainiate.bluesteal.services.tunnel

import okhttp3.OkHttpClient
import okhttp3.Request
import org.crainiate.bluesteal.BuildConfig
import org.crainiate.bluesteal.logging.RelayTracer
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Peer-side out-of-band token fetch for the "two rooms" demo. GETs the latest
 * CODE-ROUTING token the host published under a shared room (see
 * bluesteal/tools/rendezvous and specs/PAIRING_TOKEN.md).
 *
 * Config (base URL, bearer) comes from [BuildConfig], fed by local.properties
 * (gitignored) — NEVER committed to this public repo. Never logs the token or
 * bearer, only the room and HTTP status.
 *
 * NOTE: [fetch] uses OkHttp's blocking call; invoke it off the tunnel's
 * single-threaded confinement dispatcher (e.g. from Dispatchers.IO).
 */
class RendezvousClient(private val tracer: RelayTracer) {
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = BuildConfig.RENDEZVOUS_BASE_URL.isNotBlank()

    /** Current token for [room], or null if none / parse / HTTP error. */
    fun fetch(room: String): String? {
        val base = BuildConfig.RENDEZVOUS_BASE_URL.trimEnd('/')
        if (base.isEmpty()) return null
        val builder = Request.Builder().url("$base/r/$room").get()
        if (BuildConfig.RENDEZVOUS_KEY.isNotBlank()) {
            builder.header("Authorization", "Bearer ${BuildConfig.RENDEZVOUS_KEY}")
        }
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                tracer.log("rendezvous", "fetch", mapOf("room" to room, "status" to resp.code))
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JSONObject(body).optString("token").ifBlank { null }
            }
        } catch (e: Exception) {
            tracer.log("rendezvous", "fetch.failed", mapOf("room" to room, "error" to e.toString()))
            null
        }
    }
}
