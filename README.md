# PnP Üretim Takipçisi

PnP masa oyunları için 3D baskı, kart, mukavva ve özel üretim işlerini takip eden,
tamamen yerel ve çevrimdışı çalışan Linux masaüstü uygulaması.

Ürün kapsamı, veri modeli ve geliştirme fazları için `PLAN.md` dosyasına bakın.

## Ne yapar?

Bir PnP oyununun basılacak, laminasyonlanacak ve mukavvaya kaplanacak işlerini
oyun oyun, hücre hücre takip eder: elinizdeki Excel veya CSV listesini içe
aktarır, ham metinden onayınızla görev üretir, ilerlemeyi ve eksik parçaları
kaydeder, sonucu CSV'ye aktarır. Veri yalnız sizin bilgisayarınızda durur;
uygulama ağa çıkmaz, hesap istemez.

## Desteklenen platform

Linux, `x86_64`, glibc. Arayüz X11 kullanır (Wayland oturumunda XWayland
gerekir). Windows ve macOS sürümü yoktur.

## Hazır paketler

| Tür | Dosya | Kime |
| --- | --- | --- |
| Taşınabilir arşiv | `pnp-tracker-<sürüm>-linux-<mimari>.tar.gz` | her Linux dağıtımı; açıp çalıştırın |
| Arch paketi | `pnp-tracker-<sürüm>-1-<mimari>.pkg.tar.zst` | Garuda ve Arch; `pacman -U` |

İkisi de Java kurulumu gerektirmez: çalışma ortamı paketin içindedir. Paketler
imzasız dağıtılır; doğrulanmaları yayımlanan SHA-256 özetiyledir.

## Belgeler

- [Kullanım kılavuzu](docs/kullanim-kilavuzu.md) — uygulamayı ilk kez kullananlar için.
- [Örnek içe aktarma belgesi](docs/ornek-ice-aktarma.md) — XLSX ve CSV biçimleri,
  örnek dosya ve hatalı satır örnekleri.
- [Temiz Garuda doğrulaması](packaging/verify/README.md) — paketin temiz bir sanal
  makinede kurulum, güncelleme ve kaldırma turu.

## Gereksinimler

- JDK 21

## Kaynaktan doğrulama

```bash
./gradlew run
./gradlew clean check
```

`check`, ktlint denetimini ve bütün testleri çalıştırır. Bellek sınırlı bir
makinede ve sürekli tümleştirmede koşan biçimi:

```bash
./gradlew clean check --rerun-tasks --no-daemon --no-parallel --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
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

## Geliştirme durumu

Uygulama kullanılabilir durumdadır: veri modeli, içe aktarma, görev takibi, yedekleme/geri yükleme ve paketleme tamamdır. Temiz bir
Garuda ortamındaki kurulum turu (`packaging/verify/`) ve depo/CI kurulumu henüz
koşulmamıştır. Sıradaki işler `PLAN.md` `18.` bölümündedir.

## Lisans

Uygulamanın kendi kaynak kodu MIT lisanslıdır — [`LICENSE`](LICENSE). Paketle
birlikte gelen Java çalışma ortamı ve üçüncü taraf kütüphaneler kendi
lisanslarıyla dağıtılır; hangi bileşenin geldiği paketin içindeki
`THIRD_PARTY_NOTICES.md` dosyasında yazar.
