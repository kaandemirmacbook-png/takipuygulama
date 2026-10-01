package com.ornek.oglumtakip

object Config {

    // ============================================================
    //  BURAYI DOLDURUN  (3 satır)
    // ============================================================

    // 1) Sunucunuzdaki konum.php adresi (https önerilir):
    const val SERVER_URL = "https://SUNUCUNUZ.com/konum.php"

    // 2) config.php içindeki GIZLI_ANAHTAR ile BİREBİR AYNI olmalı:
    const val GIZLI_ANAHTAR = "buraya-uzun-rastgele-bir-anahtar-yaz"

    // 3) Bu telefonun panelde görünecek adı:
    const val CIHAZ_ADI = "oglum"

    // ============================================================

    // Konum gönderme sıklığı (milisaniye). 180000 = 3 dakika.
    // 60000 = 1 dakika, 300000 = 5 dakika.
    const val ARALIK_MS = 180000L
}
