package com.hyntix.android.usbflasher.download

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {

    fun downloadClient(context: Context): OkHttpClient {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val version = packageInfo?.versionName ?: "unknown"
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cache(null)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "HyntixUSBFlasher/$version (Android ${android.os.Build.VERSION.SDK_INT})")
                    .header("Accept-Encoding", "identity")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    fun checksumClient(context: Context): OkHttpClient {
        return downloadClient(context).newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
