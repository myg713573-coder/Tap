package com.tap.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class TapProfile(
    val id: String,
    val username: String,
    val email: String,
    val referralCode: String,
    val availableCoins: Long,
    val lifetimeEarned: Long,
    val energy: Int,
    val maxEnergy: Int
)

data class TapConfig(val appName: String, val coinsPerTap: Int, val maxTapsPerRequest: Int, val maxEnergy: Int)

data class TapResult(val tapCount: Int, val coinsAwarded: Long, val energy: Int, val balance: Long)

data class LedgerItem(val kind: String, val coinDelta: Long, val createdAt: String)

data class TapTask(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val rewardCoins: Long,
    val verificationType: String,
    val status: String?
)

data class RankItem(val username: String, val taps: Long, val coins: Long)

data class DailyStatus(val claimedToday: Boolean, val streak: Int, val nextReward: Long)

class TapApi(context: Context) {
    private val appContext = context.applicationContext
    private val baseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }

    fun hasSession(): Boolean = firebaseAuth.currentUser != null

    fun isOnline(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun authenticate(email: String, password: String, username: String?): JSONObject {
        val credential = if (username == null) {
            firebaseAuth.signInWithEmailAndPassword(email, password).await()
        } else {
            firebaseAuth.createUserWithEmailAndPassword(email, password).await()
        }
        val route = if (username == null) "/v1/auth/login" else "/v1/auth/register"
        return try {
            request(route = route, method = "POST", body = JSONObject().put("username", username ?: ""))
                .getJSONObject("user")
        } catch (error: Exception) {
            if (username != null) {
                runCatching { credential.user?.delete()?.await() }
                firebaseAuth.signOut()
            }
            throw error
        }
    }

    fun logout() {
        try {
            request("POST", "/v1/auth/logout", JSONObject())
        } finally {
            firebaseAuth.signOut()
        }
    }

    fun loadConfig(): TapConfig {
        val response = request("GET", "/v1/config", authorized = false)
        val game = response.getJSONObject("config").getJSONObject("game")
        return TapConfig(
            appName = response.getJSONObject("config").optString("appName", "TAP"),
            coinsPerTap = game.optInt("coinsPerTap", 1),
            maxTapsPerRequest = game.optInt("maxTapsPerRequest", 20),
            maxEnergy = game.optInt("maxEnergy", 500)
        )
    }

    fun loadProfile(maxEnergy: Int): TapProfile {
        val body = request("GET", "/v1/me")
        return TapProfile(
            id = body.getString("id"),
            username = body.getString("username"),
            email = body.optString("email"),
            referralCode = body.optString("referral_code"),
            availableCoins = body.optLong("available_coins"),
            lifetimeEarned = body.optLong("lifetime_earned"),
            energy = body.optInt("energy"),
            maxEnergy = maxEnergy
        )
    }

    fun tap(count: Int): TapResult {
        val body = request(
            "POST",
            "/v1/taps",
            JSONObject().put("tapCount", count),
            idempotencyKey = UUID.randomUUID().toString().replace("-", "")
        )
        return TapResult(
            tapCount = body.optInt("tapCount"),
            coinsAwarded = body.optLong("coinsAwarded"),
            energy = body.optInt("energy"),
            balance = body.optLong("balance")
        )
    }

    fun transactions(): List<LedgerItem> {
        val items = request("GET", "/v1/transactions").getJSONArray("items")
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            LedgerItem(item.optString("kind"), item.optLong("coin_delta"), item.optString("created_at"))
        }
    }

    fun tasks(): List<TapTask> {
        val items = request("GET", "/v1/tasks").getJSONArray("items")
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            TapTask(
                id = item.getString("id"),
                title = item.getString("title"),
                description = item.optString("description"),
                category = item.optString("category"),
                rewardCoins = item.optLong("reward_coins"),
                verificationType = item.optString("verification_type"),
                status = item.optString("submission_status").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    fun submitTask(taskId: String, proof: String) {
        request("POST", "/v1/tasks/$taskId/submit", JSONObject().put("evidence", JSONObject().put("proof", proof)))
    }

    fun dailyStatus(): DailyStatus {
        val body = request("GET", "/v1/rewards/daily")
        return DailyStatus(body.optBoolean("claimedToday"), body.optInt("streak"), body.optLong("nextReward"))
    }

    fun claimDailyReward(): JSONObject = request("POST", "/v1/rewards/daily", JSONObject())

    fun leaderboard(period: String): List<RankItem> {
        val items = request("GET", "/v1/leaderboard?period=$period").getJSONArray("items")
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            RankItem(item.optString("username"), item.optLong("taps"), item.optLong("coins"))
        }
    }

    private fun request(
        method: String,
        route: String,
        body: JSONObject? = null,
        authorized: Boolean = true,
        idempotencyKey: String? = null
    ): JSONObject {
        if (!isOnline()) throw IOException("You are offline. Connect to the internet to continue.")
        val connection = URL("$baseUrl$route").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        if (authorized) {
            val idToken = firebaseAuth.currentUser?.getIdToken(false)?.await()?.token
                ?: throw IOException("Sign in to continue.")
            connection.setRequestProperty("Authorization", "Bearer $idToken")
        }
        idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val response = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (status !in 200..299) throw IOException(response.optString("error", "Request failed ($status)"))
        return response
    }
}