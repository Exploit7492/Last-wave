package com.lastwave.app.data.lossless

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI bridge to native secrets stored in compiled ARM code.
 *
 * The Tidal API key never exists as a string in DEX/Java bytecode.
 * The native layer verifies the APK signing certificate before releasing
 * any credentials, blocking repackaged/patched builds.
 */
@Singleton
class NativeSecrets @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun baseUrl(): String = nativeBaseUrl(context)
    fun apiKey(): String = nativeApiKey(context)

    fun credentials(): BackendCredentials {
        val url = baseUrl()
        val key = apiKey()
        return if (url.isNotBlank()) {
            BackendCredentials(baseUrl = url.trimEnd('/'), apiKey = key)
        } else {
            BackendCredentials()
        }
    }

    companion object {
        init {
            System.loadLibrary("lastwave_audio")
        }

        @JvmStatic
        private external fun nativeBaseUrl(context: Context): String

        @JvmStatic
        private external fun nativeApiKey(context: Context): String
    }
}
