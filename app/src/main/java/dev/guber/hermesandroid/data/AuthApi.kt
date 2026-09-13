package dev.guber.hermesandroid.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class AuthApi(private val http: OkHttpClient = defaultClient()) {
    suspend fun mintWsTicket(endpoint: GatewayEndpoint, accessToken: String): Result<String> = withContext(Dispatchers.IO) {
        val response = post(endpoint.route("/api/auth/ws-ticket"), accessToken, JSONObject())
        response.fold(
            onSuccess = { body ->
                val ticket = body.optString("ticket")
                if (ticket.isBlank()) Result.failure(IllegalStateException("Gateway did not return a WebSocket ticket"))
                else Result.success(ticket)
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun refresh(endpoint: GatewayEndpoint, refreshToken: String, provider: String?): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("refresh_token", refreshToken)
            if (!provider.isNullOrBlank()) payload.put("provider", provider)
            post(endpoint.route("/auth/native/refresh"), null, payload).fold(
                onSuccess = { Result.success(parseSession(it, refreshToken)) },
                onFailure = { Result.failure(it) },
            )
        }

    suspend fun exchangeCode(endpoint: GatewayEndpoint, code: String, verifier: String): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("code", code).put("code_verifier", verifier)
            post(endpoint.route("/auth/native/token"), null, payload).fold(
                onSuccess = { Result.success(parseSession(it, "")) },
                onFailure = { Result.failure(it) },
            )
        }

    private fun post(url: okhttp3.HttpUrl, accessToken: String?, payload: JSONObject): Result<JSONObject> {
        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
                .header("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) builder.header("Authorization", "Bearer $accessToken")
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw GatewayHttpException(response.code, safeErrorMessage(body, response.code))
                }
                JSONObject(body)
            }
        }
    }

    private fun parseSession(body: JSONObject, fallbackRefresh: String): AuthSession {
        val access = body.optString("access_token")
        require(access.isNotBlank()) { "Authentication response did not include an access token" }
        return AuthSession(
            accessToken = access,
            refreshToken = body.optString("refresh_token").ifBlank { fallbackRefresh },
            expiresAtEpochSeconds = body.optLong("expires_at", 0),
            provider = body.optString("provider").ifBlank { null },
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun safeErrorMessage(body: String, status: Int): String {
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            val serverMessage = parsed?.optString("message")?.ifBlank { null }
                ?: parsed?.optString("error")?.ifBlank { null }
            return serverMessage?.take(180) ?: "Gateway request failed (HTTP $status)"
        }
    }
}

class GatewayHttpException(val statusCode: Int, override val message: String) : Exception(message)

/** Native OAuth flow using the RFC 8252 loopback redirect accepted by Hermes. */
class NativePkceLogin(
    private val context: Context,
    private val api: AuthApi = AuthApi(),
) {
    suspend fun start(endpoint: GatewayEndpoint): Result<AuthSession> = withContext(Dispatchers.IO) {
        runCatching {
            val verifier = randomUrlSafe(48)
            val state = randomUrlSafe(24)
            val challenge = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 180_000
                val redirect = "http://127.0.0.1:${server.localPort}"
                val authorize = endpoint.route("/auth/native/authorize").newBuilder()
                    .addQueryParameter("client_id", "hermes-android")
                    .addQueryParameter("redirect_uri", redirect)
                    .addQueryParameter("code_challenge", challenge)
                    .addQueryParameter("code_challenge_method", "S256")
                    .addQueryParameter("state", state)
                    .build()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorize.toString())).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                val callback = server.accept()
                val query = callback.readRequestTarget()
                callback.respond(query.errorMessage ?: "Hermes authentication complete. You can return to the app.")
                callback.close()
                if (query.state != state) throw IllegalStateException("Authentication state did not match")
                if (query.code.isNullOrBlank()) throw IllegalStateException(query.errorMessage ?: "Authentication was cancelled")
                api.exchangeCode(endpoint, query.code, verifier).getOrThrow()
            }
        }.fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it) })
    }

    private data class CallbackQuery(val code: String?, val state: String?, val errorMessage: String?)

    private fun Socket.readRequestTarget(): CallbackQuery {
        val firstLine = BufferedReader(InputStreamReader(getInputStream(), Charsets.US_ASCII)).readLine()
            ?: throw IllegalStateException("Empty authentication callback")
        val target = firstLine.split(' ').getOrNull(1) ?: throw IllegalStateException("Invalid authentication callback")
        val uri = Uri.parse("http://127.0.0.1$target")
        return CallbackQuery(uri.getQueryParameter("code"), uri.getQueryParameter("state"), uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error"))
    }

    private fun Socket.respond(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        getOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            writer.write(message)
            writer.flush()
        }
    }

    private companion object {
        fun randomUrlSafe(bytes: Int): String {
            val value = ByteArray(bytes)
            SecureRandom().nextBytes(value)
            return Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}

private fun defaultClient(): OkHttpClient = AuthApi.defaultClient()
