package com.ornek.oglumtakip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var durumTv: TextView

    private val izinSonuc = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { sonuclar ->
        val konumVar = sonuclar[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (konumVar) {
            arkaPlanKonumIste()
        } else {
            durumTv.text = "Konum izni verilmedi. Uygulama çalışamaz.\n" +
                    "Ayarlar > Uygulamalar > Oğlum Takip > İzinler'den konumu açın."
        }
    }

    private val arkaPlanSonuc = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> servisiBaslat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        durumTv = findViewById(R.id.durum)
        findViewById<TextView>(R.id.cihazId).text =
            "Cihaz: ${Config.CIHAZ_ADI}  (kimlik: ${cihazId().take(8)})"

        findViewById<Button>(R.id.baslatBtn).setOnClickListener { izinleriIste() }

        findViewById<Button>(R.id.durdurBtn).setOnClickListener {
            stopService(Intent(this, LocationForegroundService::class.java))
            durumTv.text = "Takip durduruldu."
        }

        findViewById<Button>(R.id.pilBtn).setOnClickListener { pilAyariniAc() }

        // SOS butonu
        findViewById<Button>(R.id.sosBtn).setOnClickListener {
            val i = Intent(this, LocationForegroundService::class.java)
            i.action = LocationForegroundService.EYLEM_SOS
            ContextCompat.startForegroundService(this, i)
            Toast.makeText(this, "SOS gönderiliyor...", Toast.LENGTH_LONG).show()
        }
    }

    private fun izinleriIste() {
        val istenecek = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            istenecek.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        izinSonuc.launch(istenecek.toTypedArray())
    }

    private fun arkaPlanKonumIste() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val verildi = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!verildi) {
                arkaPlanSonuc.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }
        servisiBaslat()
    }

    private fun servisiBaslat() {
        ContextCompat.startForegroundService(
            this, Intent(this, LocationForegroundService::class.java)
        )
        durumTv.text = "Takip çalışıyor.\n" +
                "Önemli: Uygulamayı silmeyin ve 'Pil Ayarını Aç' düğmesiyle " +
                "pil kısıtlamasını kaldırın."
    }

    private fun cihazId(): String {
        val sp = getSharedPreferences("ayar", MODE_PRIVATE)
        var id = sp.getString("cihaz_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            sp.edit().putString("cihaz_id", id).apply()
        }
        return id
    }

    private fun pilAyariniAc() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
