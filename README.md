# PnP Üretim Takipçisi

PnP masa oyunları için 3D baskı, kart, mukavva ve özel üretim işlerini takip eden,
tamamen yerel ve çevrimdışı çalışan Linux masaüstü uygulaması.

Ürün kapsamı, veri modeli ve geliştirme fazları için `PLAN.md` dosyasına bakın.

## Belgeler

- [Kullanım kılavuzu](docs/kullanim-kilavuzu.md) — uygulamayı ilk kez kullananlar için.
- [Örnek içe aktarma belgesi](docs/ornek-ice-aktarma.md) — XLSX ve CSV biçimleri,
  örnek dosya ve hatalı satır örnekleri.
- [Temiz Garuda doğrulaması](packaging/verify/README.md) — paketin temiz bir sanal
  makinede kurulum, güncelleme ve kaldırma turu.

## Gereksinimler

- JDK 21

## Komutlar

```bash
./gradlew run
./gradlew clean check
```

## Linux paketi

Sürüm tek yerde, `app/build.gradle.kts` içindeki `version` değerindedir; paket,
arşiv ve uygulama sürümü oradan türetilir.

```bash
./gradlew :app:packageLinuxArchive   # app/build/linux/dist/pnp-tracker-<sürüm>-linux-<mimari>.tar.gz
./gradlew :app:verifyLinuxPackage    # arşivi denetler ve depo dışından çalıştırır (ekran gerekir)
```

Arşiv, Java kurulumu gerektirmeyen kendi başına bir uygulama dizinidir
(`pnp-tracker-<sürüm>/bin/pnp-tracker`); içinde yalnız gereken modülleri taşıyan
bir Java çalışma ortamı bulunur.

## Garuda/Arch paketi

```bash
./gradlew :app:packageArch           # app/build/arch/dist/pnp-tracker-<sürüm>-1-<mimari>.pkg.tar.zst
./gradlew :app:verifyArchPackage     # paketi geçici bir köke açar, denetler ve çalıştırır (ekran gerekir)
sudo pacman -U app/build/arch/dist/pnp-tracker-<sürüm>-1-<mimari>.pkg.tar.zst
```

Paket, yukarıdaki arşivi `packaging/arch/PKGBUILD` ile `makepkg`'e verir; uygulama
yeniden derlenmez. Uygulama `/opt/pnp-tracker` altına, başlatıcı
`/usr/bin/pnp-tracker`, masaüstü girdisi ve simge freedesktop konumlarına kurulur.
Sistemde Java gerekmez; veriler yine kullanıcının XDG dizinlerindedir.
