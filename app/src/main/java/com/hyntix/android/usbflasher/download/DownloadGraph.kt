package com.hyntix.android.usbflasher.download

import android.annotation.SuppressLint
import android.content.Context

/**
 * Tiny manual service locator. The app deliberately has no DI framework; both
 * MainActivity and DownloadService call init() so a background start can
 * reconstruct the same graph in a fresh process.
 *
 * All stored contexts are application contexts, which live for the process.
 */
@SuppressLint("StaticFieldLeak")
object DownloadGraph {

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context
    private lateinit var downloadClient: okhttp3.OkHttpClient
    private lateinit var checksumClient: okhttp3.OkHttpClient

    lateinit var store: DownloadStore
        private set

    lateinit var catalogLoader: IsoCatalogLoader
        private set

    lateinit var repository: DownloadRepository
        private set

    fun downloadClient(): okhttp3.OkHttpClient = downloadClient

    fun init(context: Context): DownloadGraph {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    appContext = context.applicationContext
                    downloadClient = HttpClients.downloadClient(appContext)
                    checksumClient = HttpClients.checksumClient(appContext)
                    store = DownloadStore(appContext)
                    catalogLoader = IsoCatalogLoader(appContext)
                    repository = DownloadRepository(
                        appContext,
                        store,
                        catalogLoader,
                        downloadClient,
                        checksumClient
                    )
                    repository.reconcile()
                    initialized = true
                }
            }
        }
        return this
    }
}
