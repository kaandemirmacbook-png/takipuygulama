package com.ornek.oglumtakip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class LocationForegroundService : Service() {

    companion object {
        const val EYLEM_SOS = "com.ornek.oglumtakip.SOS"
        const val KOMUT_YOKLAMA_MS = 60000L   // komut.php'yi 60 saniyede bir yokla
    }

    private lateinit var konumSaglayici: FusedLocationProviderClient
    private val isci = Executors.newSingleThreadExecutor()
    private val kanalId = "takip_kanali"
    private val bekleyenDosya by lazy { File(filesDir, "bekleyen.jsonl") }
    private val handler = Handler(Looper.getMainLooper())

    private val komutRunnable = object : Runnable {
        override fun run() {
            komutKontrol()
            handler.postDelayed(this, KOMUT_YOKLAMA_MS)
        }
    }

    private val konumCallback = object : LocationCallback() {
        override fun onLocationResult(sonuc: LocationResult) {
            sonuc.lastLocation?.let { gonderVeyaBiriktir(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        konumSaglayici = LocationServices.getFusedLocationProviderClient(this)
        bildirimKanaliOlustur()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        baslatOnPlan()

        // SOS butonundan mı geldi?
        if (intent?.action == EYLEM_SOS) {
            anlikKonumGonder(sos = true)
        }

        konumGuncellemeleriniBaslat()
        // Komut yoklamayı başlat (tekrar başlatmada çift olmasın diye önce kaldır)
        handler.removeCallbacks(komutRunnable)
        handler.postDelayed(komutRunnable, KOMUT_YOKLAMA_MS)
        return START_STICKY
    }

    private fun baslatOnPlan() {
        val bildirim: Notification = NotificationCompat.Builder(this, kanalId)
            .setContentTitle("Konum paylaşılıyor")
            .setContentText("Uygulama arka planda çalışıyor.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, bildirim, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, bildirim)
        }
    }

    private fun konumGuncellemeleriniBaslat() {
        val istek = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, Config.ARALIK_MS
        ).setMinUpdateIntervalMillis(Config.ARALIK_MS).build()
        try {
            konumSaglayici.requestLocationUpdates(istek, konumCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    /** Bir Location'ı JSON noktaya çevirir */
    private fun noktaJson(loc: Location): JSONObject = JSONObject().apply {
        put("enlem", loc.latitude)
        put("boylam", loc.longitude)
        put("dogruluk", loc.accuracy)
        put("hiz", loc.speed)
        put("zaman", System.currentTimeMillis())
    }

    private fun gonderVeyaBiriktir(loc: Location) {
        val nokta = noktaJson(loc)
        isci.execute {
            val liste = JSONArray()
            if (bekleyenDosya.exists()) {
                bekleyenDosya.readLines().forEach { satir ->
                    if (satir.isNotBlank()) {
                        try { liste.put(JSONObject(satir)) } catch (_: Exception) {}
                    }
                }
            }
            liste.put(nokta)
            if (gonder(liste, sos = false)) {
                if (bekleyenDosya.exists()) bekleyenDosya.delete()
            } else {
                bekleyenDosya.appendText(nokta.toString() + "\n")
            }
        }
    }

    /** Anlık tek konum alıp hemen gönderir (SOS veya "şimdi neredesin" için) */
    private fun anlikKonumGonder(sos: Boolean) {
        try {
            val iptal = CancellationTokenSource()
            konumSaglayici.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, iptal.token)
                .addOnSuccessListener { loc ->
                    val kullan = loc
                    if (kullan != null) {
                        isci.execute {
                            val arr = JSONArray().apply { put(noktaJson(kullan)) }
                            gonder(arr, sos)
                        }
                    } else {
                        // Taze konum alınamadıysa son bilinen konumu dene
                        konumSaglayici.lastLocation.addOnSuccessListener { son ->
                            if (son != null) isci.execute {
                                val arr = JSONArray().apply { put(noktaJson(son)) }
                                gonder(arr, sos)
                            }
                        }
                    }
                }
        } catch (e: SecurityException) {
            // konum izni yok
        }
    }

    private fun gonder(noktalar: JSONArray, sos: Boolean): Boolean {
        return try {
            val sp = getSharedPreferences("ayar", MODE_PRIVATE)
            val cihazId = sp.getString("cihaz_id", "bilinmiyor")

            val govde = JSONObject().apply {
                put("anahtar", Config.GIZLI_ANAHTAR)
                put("cihaz_id", cihazId)
                put("cihaz_adi", Config.CIHAZ_ADI)
                put("noktalar", noktalar)
                put("pil", pilSeviyesi())
                put("sarj", sarjOluyorMu())
                if (sos) put("sos", true)
            }

            val baglanti = (URL(Config.SERVER_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            OutputStreamWriter(baglanti.outputStream, Charsets.UTF_8).use { it.write(govde.toString()) }
            val kod = baglanti.responseCode
            baglanti.disconnect()
            kod in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /** komut.php'yi yoklar; "1" dönerse anlık konum gönderir */
    private fun komutKontrol() {
        isci.execute {
            try {
                val sp = getSharedPreferences("ayar", MODE_PRIVATE)
                val cihazId = sp.getString("cihaz_id", "bilinmiyor") ?: "bilinmiyor"
                val taban = Config.SERVER_URL.substringBeforeLast('/')
                val url = taban + "/komut.php?anahtar=" +
                        URLEncoder.encode(Config.GIZLI_ANAHTAR, "UTF-8") +
                        "&cihaz_id=" + URLEncoder.encode(cihazId, "UTF-8")
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val cevap = c.inputStream.bufferedReader().use { it.readText() }.trim()
                c.disconnect()
                if (cevap == "1") {
                    handler.post { anlikKonumGonder(sos = false) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun pilSeviyesi(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }
    }

    private fun sarjOluyorMu(): Boolean {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.isCharging
        } catch (e: Exception) { false }
    }

    private fun bildirimKanaliOlustur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val kanal = NotificationChannel(
                kanalId, "Konum Takibi", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(kanal)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(komutRunnable)
        try { konumSaglayici.removeLocationUpdates(konumCallback) } catch (_: Exception) {}
        isci.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
