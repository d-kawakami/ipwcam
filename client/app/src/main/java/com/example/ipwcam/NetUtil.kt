package com.example.ipwcam

import java.net.Inet4Address
import java.net.NetworkInterface

/** 端末のIPv4アドレス判定(LAN / Tailscale / その他)。 */
object NetUtil {

    data class LocalAddrs(
        val lan: String?,        // Wi-Fi/有線などのLANアドレス
        val tailscale: String?,  // Tailscale(100.64.0.0/10)
        val other: String?       // 上記以外(モバイル回線など。最後の手段)
    )

    /** Tailscaleが使う 100.64.0.0/10 (100.64.x.x ～ 100.127.x.x) か */
    fun isTailscaleIp(ip: String): Boolean {
        val p = ip.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    // モバイル回線のインターフェース名(LANとして扱わない)
    private val CELLULAR_NAME = Regex("^(v4-)?(rmnet|ccmni|pdp|ppp|clat|ccinet|usbnet).*", RegexOption.IGNORE_CASE)

    fun findLocalAddrs(): LocalAddrs {
        var lan: String? = null
        var ts: String? = null
        var other: String? = null
        val interfaces = try { NetworkInterface.getNetworkInterfaces() } catch (_: Exception) { null }
            ?: return LocalAddrs(null, null, null)
        for (intf in interfaces) {
            try {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    when {
                        isTailscaleIp(ip) -> if (ts == null) ts = ip
                        CELLULAR_NAME.matches(intf.name) -> if (other == null) other = ip
                        else -> if (lan == null) lan = ip
                    }
                }
            } catch (_: Exception) {
            }
        }
        return LocalAddrs(lan, ts, other)
    }

    /** この端末自身がTailscaleに接続しているか */
    fun hasTailscale(): Boolean = findLocalAddrs().tailscale != null
}
