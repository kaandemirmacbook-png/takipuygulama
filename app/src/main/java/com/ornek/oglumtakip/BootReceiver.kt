package com.ornek.oglumtakip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Telefon yeniden başlatılınca takibi tekrar başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, LocationForegroundService::class.java)
                )
            } catch (_: Exception) {
                // Bazı Android sürümleri açılışta kısıtlayabilir; kullanıcı uygulamayı
                // bir kez açınca takip yine devam eder.
            }
        }
    }
}
