PnP Üretim Takipçisi — Linux uygulama dizini
============================================

Bu dizin, sistemde Java kurulu olmasını gerektirmeyen, kendi başına çalışan
Linux uygulamasıdır.

Çalıştırmak için:

    ./bin/pnp-tracker

İçerik:

    bin/pnp-tracker      uygulama başlatıcısı
    lib/app/             uygulamanın ve kütüphanelerinin jar dosyaları,
                         Compose/Skia için gerekli native kütüphane
    lib/runtime/         uygulamayla gelen, yalnız gereken modülleri içeren
                         Java çalışma ortamı (modül listesi: lib/runtime/release)
    VERSION              paket adı, sürümü ve mimarisi

Uygulama bu dizine hiçbir şey yazmaz. Veriler kullanıcının XDG dizinlerinde
tutulur:

    $XDG_DATA_HOME/pnp-tracker/     veritabanı ve yedekler
                                    (tanımsızsa ~/.local/share/pnp-tracker/)
    $XDG_CONFIG_HOME/pnp-tracker/   ayarlar (tanımsızsa ~/.config/pnp-tracker/)
    $XDG_STATE_HOME/pnp-tracker/    tanılama kayıtları
                                    (tanımsızsa ~/.local/state/pnp-tracker/)

Bu dizini silmek verilerinize dokunmaz.

Lisans
------

Uygulamanın kendi kaynak kodu MIT lisanslıdır; metin bu dizindeki LICENSE
dosyasındadır. Uygulamayla gelen Java çalışma ortamı ve üçüncü taraf
kütüphaneler kendi lisanslarıyla dağıtılır; bunlar THIRD_PARTY_NOTICES.md
dosyasında, lisans metinleri ise third-party/ ve lib/runtime/legal/ altında
bulunur.
