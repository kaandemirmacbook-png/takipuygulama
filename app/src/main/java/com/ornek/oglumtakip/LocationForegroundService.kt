package com.ornek.oglumtakip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class LocationForegroundService : Service() {

    private lateinit var konumSaglayici: FusedLocationProviderClient
    private val isci = Executors.newSingleThreadExecutor()
    private val kanalId = "takip_kanali"
    private val bekleyenDosya by lazy { File(filesDir, "bekleyen.jsonl") }

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
        konumGuncellemeleriniBaslat()
        return START_STICKY  // sistem öldürürse yeniden başlat
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
            // Konum izni yok; servisi durdur
            stopSelf()
        }
    }

    private fun gonderVeyaBiriktir(loc: Location) {
        val nokta = JSONObject().apply {
            put("enlem", loc.latitude)
            put("boylam", loc.longitude)
            put("dogruluk", loc.accuracy)
            put("hiz", loc.speed)
            put("zaman", System.currentTimeMillis())
        }

        isci.execute {
            val liste = JSONArray()
            // Önce internet yokken birikmiş noktalar
            if (bekleyenDosya.exists()) {
                bekleyenDosya.readLines().forEach { satir ->
                    if (satir.isNotBlank()) {
                        try { liste.put(JSONObject(satir)) } catch (_: Exception) {}
                    }
                }
            }
            liste.put(nokta)

            if (gonder(liste)) {
                // Hepsi gitti; tamponu temizle
                if (bekleyenDosya.exists()) bekleyenDosya.delete()
            } else {
                // İnternet yok/hata: sadece yeni noktayı tampona ekle
                bekleyenDosya.appendText(nokta.toString() + "\n")
            }
        }
    }

    private fun gonder(noktalar: JSONArray): Boolean {
        return try {
            val sp = getSharedPreferences("ayar", MODE_PRIVATE)
            val cihazId = sp.getString("cihaz_id", "bilinmiyor")

            val govde = JSONObject().apply {
                put("anahtar", Config.GIZLI_ANAHTAR)
                put("cihaz_id", cihazId)
                put("cihaz_adi", Config.CIHAZ_ADI)
                put("noktalar", noktalar)
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
        try { konumSaglayici.removeLocationUpdates(konumCallback) } catch (_: Exception) {}
        isci.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
