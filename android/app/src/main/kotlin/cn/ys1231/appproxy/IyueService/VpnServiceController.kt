package cn.ys1231.appproxy.IyueService

import android.content.Context
import android.util.Log
import cn.ys1231.appproxy.data.Utils

class VpnServiceController(
    private val context: Context,
    private var vpnService: IyueVPNService?,
    private val utils: Utils
) {
    private val TAG = "iyue->${this.javaClass.simpleName}"
    private var currentProxy: MutableMap<String, Any>? = HashMap()
    private var fields = listOf("proxyPort","proxyPass","proxyName","proxyType","proxyUser","appProxyPackageList","proxyHost")

    fun updateVpnService(newVpnService: IyueVPNService?) {
        vpnService = newVpnService
        Log.d(TAG, "VPN service updated: $newVpnService")
    }

//    fun isServiceAvailable(): Boolean {
//        return true
//    }

    fun configChange(config: Map<String, Any>) {
        for (key in config.keys) {
            if (fields.contains(key)) {
                currentProxy!![key] = config[key]!!
            }
        }
    }

    fun startVpn(config: Map<String, Any>): String {
        return try {
            // {proxyPort=8080, proxyPass=, proxyName=vpn, proxyType=http, proxyUser=, appProxyPackageList=["mx.com.bancoazteca.bazdigitalmovil"], proxyHost=192.168.0.11}
            vpnService?.startVpnService(config)
            "VPN started successfully"
        } catch (e: Exception) {
            "Error to start VPN: ${e.message}"
        }
    }

    fun stopVpn(): String {
        return try {
            run {
                vpnService?.stopVpnService()
                "VPN stopped state:${vpnService?.isRunning()}"
            }
        } catch (e: Exception) {
            "Error to stop VPN: ${e.message}"

        }
    }

    fun getVpnStatus(): String {
        return "VPN state:${vpnService?.isRunning()}"
    }

}