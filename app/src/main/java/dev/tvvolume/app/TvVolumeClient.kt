package dev.tvvolume.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Process
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TvVolumeClient {

    private const val TAG = "TvVolume"
    private const val PREFS = "tv_volume"
    private const val KEY_CONTROL_URL = "control_url"
    private const val KEY_STATUS = "last_status"
    private const val KEY_TV_IP = "tv_ip"

    const val FALLBACK_TV_VOLUME = 8

    private const val KEY_APP_PREFIX = "appvol:"

    private val DEFAULT_VOLUMES = mapOf(
        "com.google.android.youtube.tv" to 8,
        "com.netflix.ninja" to 20,
        "com.apple.atve.androidtv.appletv" to 30
    )

    private val EXCLUDED_PACKAGES = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.android.systemui",
        "com.droidlogic"
    )

    @Volatile
    private var controlUrl: String? = null

    @Volatile
    private var lastHttpCode: String? = null

    fun warmup(context: Context) {
        val app = context.applicationContext
        Thread({ resolve(app) }, "tv-volume-discover").start()
    }

    fun resetToDefault(context: Context) {
        val app = context.applicationContext
        Thread({
            val pkg = foregroundPackage(app)
            val volume = volumeFor(app, pkg)
            Log.i(TAG, "Foreground=" + pkg + " -> volume " + volume)
            resetWithRetry(app, pkg ?: "unknown", volume)
        }, "tv-volume-reset").start()
    }

    fun lastStatus(context: Context): String? {
        return prefs(context).getString(KEY_STATUS, null)
    }

    private fun saveStatus(context: Context, text: String) {
        val stamp = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        prefs(context).edit().putString(KEY_STATUS, stamp + " " + text).apply()
    }

    private fun resetWithRetry(context: Context, label: String, volume: Int) {
        if (trySetVolume(context, volume)) {
            Log.i(TAG, label + " -> " + volume + " OK")
            saveStatus(context, label + " -> " + volume + " OK")
            return
        }
        try {
            Thread.sleep(8000)
        } catch (e: InterruptedException) {
            return
        }
        if (trySetVolume(context, volume)) {
            Log.i(TAG, label + " -> " + volume + " OK (retry)")
            saveStatus(context, label + " -> " + volume + " OK (retry)")
        } else {
            Log.w(TAG, label + " -> " + volume + " FAIL (HTTP " + (lastHttpCode ?: "?") + ")")
            saveStatus(context, label + " -> " + volume + " FAIL (HTTP " + (lastHttpCode ?: "?") + ")")
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun foregroundPackage(context: Context): String? {
        statsWay(context)?.let { return it }
        eventsWay(context)?.let { return it }
        val pkg = AppWatcherService.currentPackage
        val tracked = AppWatcherService.lastTrackedPackage
        Log.i(TAG, "a11y picked=$pkg tracked=$tracked")
        if (pkg != null && isTracked(context, pkg)) return pkg
        return tracked
    }

    fun isTracked(context: Context, pkg: String): Boolean {
        return pkg != context.packageName && pkg !in EXCLUDED_PACKAGES
    }

    private fun statsWay(context: Context): String? {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 24 * 60 * 60 * 1000, end)
            if (stats.isNullOrEmpty()) {
                Log.i(TAG, "stats empty")
                return null
            }
            return stats
                .filter {
                    it.packageName != context.packageName && it.packageName !in EXCLUDED_PACKAGES
                }
                .maxByOrNull { it.lastTimeUsed }
                ?.packageName
        } catch (e: Exception) {
            Log.w(TAG, "Usage stats query failed: " + e.message)
            return null
        }
    }

    private fun eventsWay(context: Context): String? {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 10 * 60 * 1000, end)
            val ev = UsageEvents.Event()
            var last: String? = null
            var lastTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    ev.packageName != context.packageName &&
                    ev.packageName !in EXCLUDED_PACKAGES &&
                    ev.timeStamp > lastTime
                ) {
                    lastTime = ev.timeStamp
                    last = ev.packageName
                }
            }
            Log.i(TAG, "events picked=$last")
            return last
        } catch (e: Exception) {
            Log.w(TAG, "Usage events query failed: " + e.message)
            return null
        }
    }

    private fun trySetVolume(context: Context, volume: Int): Boolean {
        val resolved = resolve(context) ?: return false
        if (resolved.second.isEmpty()) return false
        val current = readVolume(resolved.first) ?: return false
        if (current == volume) return true
        return postSetVolume(resolved.first, volume)
    }

    private fun resolve(context: Context): Pair<String, String>? {
        prefs(context).getString(KEY_CONTROL_URL, null)?.let {
            controlUrl = it
            return it to hostOf(it)
        }
        manualDescUrl(context)?.let { desc ->
            findControlUrl(desc)?.let {
                save(context, it)
                return it to hostOf(it)
            }
        }
        return discover(context)?.let {
            save(context, it)
            it to hostOf(it)
        }
    }

    private fun hostOf(url: String): String {
        return try {
            URL(url).host
        } catch (e: Exception) {
            ""
        }
    }

    fun tvIp(context: Context): String? {
        return prefs(context).getString(KEY_TV_IP, null)
    }

    fun setTvIp(context: Context, ip: String) {
        prefs(context).edit()
            .putString(KEY_TV_IP, ip.trim())
            .remove(KEY_CONTROL_URL)
            .apply()
        controlUrl = null
    }

    private fun manualDescUrl(context: Context): String? {
        val ip = prefs(context).getString(KEY_TV_IP, null)?.trim() ?: return null
        if (ip.isEmpty()) return null
        if (ip.contains("://")) return ip
        return "http://" + ip + ":9197/dmr"
    }

    private fun discover(context: Context): String? {
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("tvvolume")?.also { it.acquire() }
            val request = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ns=01\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "USER-AGENT: TVVolume/1.0 UPnP/1.1\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            val target = InetSocketAddress("239.255.255.250", 1900)
            val candidates = mutableListOf<Pair<String, String>>()
            DatagramSocket().use { socket ->
                repeat(2) {
                    socket.send(DatagramPacket(request, request.size, target))
                    val deadline = System.currentTimeMillis() + 1500
                    while (true) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) break
                        socket.soTimeout = remaining.toInt().coerceAtLeast(1)
                        val buf = ByteArray(8192)
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (e: SocketTimeoutException) {
                            break
                        }
                        parseResponse(packet)?.let { candidates.add(it) }
                    }
                }
            }
            Log.i(TAG, "Discovery saw " + candidates.size + " renderer(s)")
            val picked = pick(candidates) ?: return null
            Log.i(TAG, "Using TV at " + picked)
            return findControlUrl(picked)
        } catch (e: Exception) {
            Log.w(TAG, "Discovery failed: " + e.message)
            return null
        } finally {
            try {
                lock?.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Multicast lock release failed")
            }
        }
    }

    private fun parseResponse(packet: DatagramPacket): Pair<String, String>? {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        var location: String? = null
        var server = ""
        for (line in text.split("\r\n", "\n")) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("LOCATION", ignoreCase = true)) location = value
                if (name.equals("SERVER", ignoreCase = true)) server = value
            }
        }
        return location?.let { it to server }
    }

    private fun pick(candidates: List<Pair<String, String>>): String? {
        return candidates.firstOrNull {
            it.first.contains(":9197/") || it.second.contains("samsung", ignoreCase = true)
        }?.first ?: candidates.firstOrNull()?.first
    }

    private fun findControlUrl(descUrl: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(descUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode != 200) return null
            val parser = Xml.newPullParser()
            parser.setInput(conn.inputStream, "UTF-8")
            var inService = false
            var serviceType: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "service" -> {
                            inService = true
                            serviceType = null
                        }
                        "serviceType" -> if (inService) serviceType = parser.nextText().trim()
                        "controlURL" -> if (inService && serviceType?.contains("RenderingControl") == true) {
                            return URL(URL(descUrl), parser.nextText().trim()).toString()
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "service") {
                    inService = false
                }
                event = parser.next()
            }
            Log.w(TAG, "No RenderingControl in $descUrl")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Device description fetch failed: " + e.message)
            return null
        } finally {
            conn?.disconnect()
        }
    }


    private fun postSetVolume(control: String, volume: Int): Boolean {        val body =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<Channel>Master</Channel>" +
                "<DesiredVolume>" + volume + "</DesiredVolume>" +
                "</u:SetVolume>" +
                "</s:Body></s:Envelope>"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(control).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty(
                "SOAPACTION",
                "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\""
            )
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            lastHttpCode = code.toString()
            Log.i(TAG, "SetVolume($volume) -> HTTP $code")
            return code == 200
        } catch (e: Exception) {
            lastHttpCode = "net-err"
            Log.w(TAG, "SetVolume failed: " + e.message)
            return false
        } finally {
            conn?.disconnect()
        }
    }

    private fun readVolume(control: String): Int? {
        val body =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:GetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<Channel>Master</Channel>" +
                "</u:GetVolume>" +
                "</s:Body></s:Envelope>"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(control).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty(
                "SOAPACTION",
                "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\""
            )
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            lastHttpCode = code.toString()
            if (code != 200) {
                Log.i(TAG, "GetVolume -> HTTP $code")
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val tag = "<CurrentVolume>"
            val start = text.indexOf(tag)
            val end = text.indexOf("</CurrentVolume>")
            if (start < 0 || end < 0) return null
            return text.substring(start + tag.length, end).trim().toIntOrNull()
        } catch (e: Exception) {
            lastHttpCode = "net-err"
            Log.w(TAG, "GetVolume failed: " + e.message)
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureSeeded(context: Context) {
        val p = prefs(context)
        if (p.all.keys.none { it.startsWith(KEY_APP_PREFIX) }) {
            p.edit().apply {
                for ((pkg, vol) in DEFAULT_VOLUMES) putInt(KEY_APP_PREFIX + pkg, vol)
            }.apply()
        }
    }

    fun volumeMappings(context: Context): Map<String, Int> {
        val out = LinkedHashMap<String, Int>(DEFAULT_VOLUMES)
        for ((k, v) in prefs(context).all) {
            if (k.startsWith(KEY_APP_PREFIX) && v is Int) {
                out[k.removePrefix(KEY_APP_PREFIX)] = v
            }
        }
        return out
    }

    fun volumeFor(context: Context, pkg: String?): Int {
        if (pkg != null) {
            val override = prefs(context).getInt(KEY_APP_PREFIX + pkg, -1)
            if (override >= 0) return override
            DEFAULT_VOLUMES[pkg]?.let { return it }
        }
        return FALLBACK_TV_VOLUME
    }

    fun setVolumeMapping(context: Context, pkg: String, vol: Int) {
        prefs(context).edit().putInt(KEY_APP_PREFIX + pkg, vol).apply()
    }

    fun removeVolumeMapping(context: Context, pkg: String) {
        prefs(context).edit().remove(KEY_APP_PREFIX + pkg).apply()
    }

    private fun save(context: Context, url: String) {
        controlUrl = url
        prefs(context).edit().putString(KEY_CONTROL_URL, url).apply()
    }
}
