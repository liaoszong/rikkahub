package me.rerere.rikkahub.web

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import me.rerere.rikkahub.utils.logSafeError
import me.rerere.rikkahub.utils.logSafeFailure
import me.rerere.rikkahub.utils.logSafeStarted
import me.rerere.rikkahub.utils.logSafeSuccess
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private const val TAG = "NsdServiceRegistrar"
private const val DEFAULT_SERVICE_TYPE = "_http._tcp.local."
const val DEFAULT_SERVICE_NAME = "rikkahub"

data class RegisteredServiceInfo(
    val serviceName: String,
    val hostname: String,
    val port: Int,
    val address: InetAddress
)

class NsdServiceRegistrar(
    private val context: Context
) {
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun register(
        port: Int,
        serviceName: String = DEFAULT_SERVICE_NAME,
        serviceType: String = DEFAULT_SERVICE_TYPE,
        onRegistered: ((RegisteredServiceInfo) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (jmdns != null) {
            unregister()
        }

        try {
            // Acquire multicast lock for mDNS
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("jmdns-lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val address = getLocalIpAddress()
            if (address == null) {
                logSafeFailure(TAG, "web", "resolve_local_address")
                return@withContext
            }

            logSafeStarted(TAG, "web", "register_nsd_service")

            // Create JmDNS instance with custom hostname
            // This will register hostname.local -> IP address
            val mdns = JmDNS.create(address, serviceName)
            jmdns = mdns

            // Register HTTP service
            val serviceInfo = ServiceInfo.create(
                serviceType,
                serviceName,
                port,
                "RikkaHub Web Server"
            )
            mdns.registerService(serviceInfo)

            logSafeSuccess(TAG, "web", "register_nsd_service")

            onRegistered?.invoke(
                RegisteredServiceInfo(
                    serviceName = serviceName,
                    hostname = "$serviceName.local",
                    port = port,
                    address = address
                )
            )
        } catch (e: Exception) {
            logSafeError(TAG, "web", "register_nsd_service", e)
            cleanup()
        }
    }

    suspend fun unregister() = withContext(Dispatchers.IO) {
        cleanup()
    }

    private fun cleanup() {
        runCatching {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        }.onFailure {
            logSafeError(TAG, "web", "close_jmdns", it, warning = true)
        }
        jmdns = null

        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }.onFailure {
            logSafeError(TAG, "web", "release_multicast_lock", it, warning = true)
        }
        multicastLock = null

        logSafeSuccess(TAG, "web", "unregister_nsd_service")
    }

    private fun getLocalIpAddress(): InetAddress? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: return null

            if (ipInt == 0) return null

            val ipBytes = byteArrayOf(
                (ipInt and 0xff).toByte(),
                (ipInt shr 8 and 0xff).toByte(),
                (ipInt shr 16 and 0xff).toByte(),
                (ipInt shr 24 and 0xff).toByte()
            )
            InetAddress.getByAddress(ipBytes)
        } catch (e: Exception) {
            logSafeError(TAG, "web", "resolve_local_address", e)
            null
        }
    }
}
