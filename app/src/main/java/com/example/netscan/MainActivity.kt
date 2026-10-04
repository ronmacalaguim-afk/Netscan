package com.example.netscan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var out: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var pending: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
        }
        val wifiBtn = Button(this).apply { text = "Scan nearby Wi-Fi (name / MAC / signal)" }
        val lanBtn = Button(this).apply { text = "Scan my network (hosts / MAC / ping)" }
        val pwBtn = Button(this).apply { text = "Open Wi-Fi settings (view/share saved password)" }
        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; text = "Tap a button to scan." }
        root.addView(wifiBtn)
        root.addView(lanBtn)
        root.addView(pwBtn)
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(root)

        wifiBtn.setOnClickListener { withPermission { scanWifi() } }
        lanBtn.setOnClickListener { scanLan() }
        pwBtn.setOnClickListener { openWifiSettings() }
    }

    private fun withPermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) pending?.invoke()
        else show("Location permission is required by Android to scan Wi-Fi.")
        pending = null
    }

    private fun show(text: String) = ui.post { out.text = text }

    // Android blocks apps from reading saved Wi-Fi passwords directly.
    // This opens the system Wi-Fi settings list; from there, tap a saved
    // network > Share to see its QR code / password.
    private fun openWifiSettings() {
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    // ---- Nearby Wi-Fi networks: name (SSID), MAC (BSSID), signal ----
    private fun scanWifi() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        show("Scanning Wi-Fi...")
        @Suppress("DEPRECATION") wm.startScan()
        ui.postDelayed({
            val results = try { wm.scanResults } catch (e: SecurityException) { emptyList() }
            val sb = StringBuilder("Found ${results.size} networks\n\n")
            results.sortedByDescending { it.level }.forEach {
                @Suppress("DEPRECATION") val name = if (it.SSID.isNullOrEmpty()) "<hidden>" else it.SSID
                sb.append("$name\n  MAC: ${it.BSSID}\n  Signal: ${it.level} dBm | ${it.frequency} MHz\n\n")
            }
            show(sb.toString())
        }, 3000)
    }

    // ---- Devices on my own network: IP, MAC (if allowed), ping time ----
    private fun scanLan() {
        show("Scanning local network...")
        Thread {
            val ip = localIpv4()
            if (ip == null) { show("Not connected to a Wi-Fi/LAN network."); return@Thread }
            val base = ip.substringBeforeLast(".")
            val alive = ConcurrentHashMap<String, Long>()
            val pool = Executors.newFixedThreadPool(48)
            val latch = CountDownLatch(254)
            for (i in 1..254) pool.execute {
                val host = "$base.$i"
                try {
                    val t = System.nanoTime()
                    if (InetAddress.getByName(host).isReachable(700)) {
                        alive[host] = (System.nanoTime() - t) / 1_000_000
                    }
                } catch (_: Exception) {}
                latch.countDown()
            }
            latch.await()
            pool.shutdown()

            val arp = readArp()
            val sb = StringBuilder("My IP: $ip\nHosts found: ${alive.size}\n\n")
            alive.keys.sortedBy { it.substringAfterLast(".").toInt() }.forEach { host ->
                val mac = arp[host] ?: "N/A"
                sb.append("$host${if (host == ip) " (this phone)" else ""}\n  MAC: $mac\n  Ping: ${alive[host]} ms\n\n")
            }
            if (arp.isEmpty()) sb.append("Note: Android 10+ blocks reading other devices' MAC addresses.")
            show(sb.toString())
        }.start()
    }

    private fun localIpv4(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
    }

    private fun readArp(): Map<String, String> = try {
        File("/proc/net/arp").readLines().drop(1).mapNotNull {
            val p = it.trim().split(Regex("\\s+"))
            if (p.size >= 4 && p[3] != "00:00:00:00:00:00") p[0] to p[3] else null
        }.toMap()
    } catch (_: Exception) { emptyMap() }
}
