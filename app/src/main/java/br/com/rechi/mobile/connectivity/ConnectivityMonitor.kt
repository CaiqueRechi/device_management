package br.com.rechi.mobile.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class ConnectivityMonitor(
    context: Context,
    private val onConnectivityChanged: (Boolean) -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishState()
        override fun onLost(network: Network) = publishState()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            publishState()
    }

    fun start() {
        if (registered) return
        registered = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        publishState()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun publishState() {
        val connected = hasValidatedInternet()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onConnectivityChanged(connected)
        }
    }
}
