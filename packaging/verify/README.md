# Temiz Garuda doğrulaması (Faz 3 / İş 13)

Bu klasör, **temiz bir Garuda/Arch sanal makinesinde** paketin kurulumunu,
açılışını, veri dizinini, güncellenmesini ve kaldırılmasını doğrulamak içindir.
Geliştirme makinesinde çalıştırılmaz: orada "temiz ortam" koşulu sağlanamaz.

## Sanal makineye ne kopyalanır

1. Bu klasörün tamamı (`packaging/verify/`).
2. Doğrulanacak paket dosyası: `pnp-tracker-<sürüm>-1-x86_64.pkg.tar.zst`.
3. İsteğe bağlı: paketin yanında `…pkg.tar.zst.sha256` dosyası (üretim
   makinesinde `sha256sum pnp-tracker-… > pnp-tracker-….sha256` ile üretilir).
4. Kullanım turunda içe aktarılacak bir örnek dosya: `app/src/desktopTest/
   resources/sample-import.xlsx` ya da kendi hazırladığınız bir CSV
   (bkz. `docs/ornek-ice-aktarma.md`).

## Tek komut

```bash
bash packaging/verify/verify-clean-install.sh ./pnp-tracker-<sürüm>-1-x86_64.pkg.tar.zst
```

Eski bir sürümden yükseltmeyi de ölçmek isterseniz:

```bash
bash packaging/verify/verify-clean-install.sh ./pnp-tracker-<yeni>-1-x86_64.pkg.tar.zst \
  --eski-paket ./pnp-tracker-<eski>-1-x86_64.pkg.tar.zst
```

Yalnız ortamı ve paketi kontrol etmek, hiçbir şey kurmamak için:

```bash
bash packaging/verify/preflight.sh ./pnp-tracker-<sürüm>-1-x86_64.pkg.tar.zst
```

## Kurallar

- Script normal kullanıcı olarak çalıştırılır. Root olarak çalıştırılırsa durur.
- Yükseltilmiş yetki yalnız `pacman` adımlarında kullanılır ve her seferinde
  ekranda duyurulur.
- Kurulum öncesinde ne yapılacağı yazılır ve `EVET` yazmadan hiçbir şey kurulmaz.
- Uygulama, bu koşuya ait geçici XDG dizinleriyle açılır; menüden açma adımı
  dışında makinedeki kendi verilerinize dokunulmaz. O adım en sondadır ve
  bilerek kendi dizinlerinizi kullanır.
- Script **hiçbir pencereyi kapatmaz**. Her açılışta pencereyi siz kapatırsınız;
  script uygulamanın çıkışını bekler.
- Silinen tek şey, scriptin kendi oluşturduğu `pnp-verify-…` geçici dizinidir;
  yolu silmeden önce doğrulanır. Kullanıcı verisi hiçbir adımda silinmez.
- Bir adım düşerse script durur ve hangi aşamada kaldığını yazar.

## Otomatik denetlenenler

- Ortam: Garuda/Arch, `x86_64`, glibc, grafik oturumu, gerekli araçlar.
- Paketin kurulu olmadığı ve kurulum yerlerinin boş olduğu.
- Paket dosyasının adı, sürümü, SHA-256'sı, `.PKGINFO` alanları, bağımlılıkları
  (sistem Java'sı olmamalı) ve içeriğinin yalnız `/opt` ile `/usr` altında olduğu.
- Kurulum sonrası dört yer, sahiplik ve izinler, `/usr/bin` bağlantısının `/opt`
  altına çözülmesi, gömülü runtime'ın varlığı, `pacman -Qkk` bütünlüğü,
  masaüstü girdisinin geçerliliği.
- Uygulamanın `PATH`'te java olmadan ve `JAVA_HOME` tanımsızken açılması,
  çıkış kodu, veritabanının oluşması, `-wal`/`-shm` kalmaması.
- Dışa aktarılan CSV'nin başlığı ve BOM'u, manuel yedek dosyasının biçimi,
  geri yükleme öncesi güvenlik yedeğinin oluşması.
- Tanılama kaydının yazılması ve içinde kullanıcı adı ya da ev dizini yolu
  bulunmaması.
- Aynı sürümün yeniden kurulmasının ve kaldırmanın kullanıcı verisine
  dokunmaması; kaldırmadan sonra dört yerin de silinmesi.
- Gerçek ev dizininin üst düzey girdilerinin değişmemesi.
- Arkada uygulama süreci kalmaması ve geçici dizinin silinmesi.

## Gözle bakılacaklar (kısa liste)

Script bunları tek tek sorar; cevabınız sonuç matrisine yazılır.

1. Pencere açıldı ve başlığı **PnP Üretim Takipçisi**.
2. İçe aktarma taslağı, onay ve geri yükleme ekranları Türkçe ve anlaşılır.
3. Yeniden açılışta oyunlar ve görevler yerinde.
4. Kaldırıp yeniden kurduktan sonra veriler yine yerinde.
5. Menüden (Uygulamalar) açılıyor ve simgesi görünüyor.

## Raporlama

Script sonunda PASS / FAIL / SKIPPED / MANUAL / INFO matrisi yazar. Raporu
saklamak isterseniz çıktıyı kendiniz bir dosyaya yönlendirin:

```bash
bash packaging/verify/verify-clean-install.sh ./paket.pkg.tar.zst | tee ./dogrulama-raporu.txt
```

Rapor dosyası bu depoya eklenmez: makineye ve kullanıcıya ait bilgi taşır.
