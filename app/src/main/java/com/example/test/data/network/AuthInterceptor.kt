package com.example.test.data.network

import com.example.test.data.local.SecurePreferences
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val securePrefs: SecurePreferences
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = securePrefs.getToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
