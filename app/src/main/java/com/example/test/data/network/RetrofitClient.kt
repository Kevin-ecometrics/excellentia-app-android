package com.example.test.data.network

import android.content.Context
import android.content.Intent
import com.example.test.data.RefreshRequest
import com.example.test.data.RefreshResponse
import com.example.test.data.local.SecurePreferences
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Authenticator
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var api: ApiService? = null
    private var currentSecurePrefs: SecurePreferences? = null

    const val ACTION_SESSION_EXPIRED = "com.example.test.SESSION_EXPIRED"

    private val gson = Gson()
    private val refreshClient = OkHttpClient()

    fun initialize(baseUrl: String, securePrefs: SecurePreferences, context: Context? = null) {
        currentSecurePrefs = securePrefs

        val logging = HttpLoggingInterceptor().apply {
            level = if (com.example.test.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(securePrefs))
            .addInterceptor(logging)
            .authenticator(TokenAuthenticator(securePrefs, context))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(ApiService::class.java)
    }

    fun getApi(): ApiService {
        return api ?: throw IllegalStateException("RetrofitClient no inicializado. Llama initialize() primero.")
    }

    fun getBackendUrl(): String? {
        return currentSecurePrefs?.getBackendUrl()
    }

    private class TokenAuthenticator(
        private val securePrefs: SecurePreferences,
        private val context: Context?
    ) : Authenticator {

        override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
            // Don't retry if the request was already to the refresh endpoint
            if (response.request.url.encodedPath.contains("/api/auth/refresh")) {
                notifySessionExpired()
                return null
            }

            val refreshToken = securePrefs.getRefreshToken() ?: run {
                notifySessionExpired()
                return null
            }

            // Check if another retry already refreshed the token
            val currentToken = securePrefs.getToken()
            val currentAuth = response.request.header("Authorization")
            if (currentToken != null && currentAuth != "Bearer $currentToken") {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            try {
                val backendUrl = securePrefs.getBackendUrl()
                val normalizedUrl = if (backendUrl.endsWith("/")) backendUrl else "$backendUrl/"

                val json = gson.toJson(RefreshRequest(refreshToken))
                val body = json.toRequestBody("application/json".toMediaType())
                val request = okhttp3.Request.Builder()
                    .url("${normalizedUrl}api/auth/refresh")
                    .post(body)
                    .build()

                val refreshResponse = refreshClient.newCall(request).execute()
                val responseBody = refreshResponse.body?.string()

                if (refreshResponse.isSuccessful && responseBody != null) {
                    val result = gson.fromJson(responseBody, RefreshResponse::class.java)
                    securePrefs.saveToken(result.token)
                    securePrefs.saveRefreshToken(result.refreshToken)
                    return response.request.newBuilder()
                        .removeHeader("Authorization")
                        .build()
                } else {
                    notifySessionExpired()
                    return null
                }
            } catch (_: Exception) {
                notifySessionExpired()
                return null
            }
        }

        private fun notifySessionExpired() {
            securePrefs.clearAll()
            context?.sendBroadcast(Intent(ACTION_SESSION_EXPIRED))
        }
    }
}
