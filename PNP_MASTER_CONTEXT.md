# PNP — MASTER CONTEXT

> Bu dosya, yeni bir AI/Claude Code oturumunun eski sohbetleri baştan okumadan doğru
> çalışma bağlamını kurabilmesi içindir: **mevcut mimari, veri modeli, invariant'lar,
> UX kuralları ve geliştirmenin bulunduğu nokta.**
>
> **PLAN.md tek yetkili kaynaktır.** Bu dosya PLAN.md'nin yerine geçmez, onu özetler ve
> repo durumuyla ilişkilendirir. Çelişki hâlinde PLAN.md kazanır.
>
> **Son güncelleme:** Faz 3 / **İş 11 ve İş 12 TAMAMLANDI** —
> `docs: record Linux packaging decisions and results`. Uygulama artık sistemde
> Java gerektirmeyen, belirlenimci bir `pnp-tracker-0.1.0-linux-x86_64.tar.gz`
> arşivi (jpackage uygulama imajı + 13 modüllük jlink runtime) ve bu arşivi
> paketleyen `pnp-tracker-0.1.0-1-x86_64.pkg.tar.zst` Arch paketi olarak
> üretiliyor; sürümün tek kaynağı Gradle `project.version` (R5 kapandı, R7
> kapandı). İki paket de gerçek başlatıcılarıyla, depo dışından, geçici XDG
> dizinleriyle çalıştırılıp `SafeWindowCloser` ile kapatıldı. Room 8, PLAN ve
> bağımlılıklar değişmedi. Tam koşu 3701 / 0 / 0 / 0 (269 sınıf). Ayrıntı §25.5.
> **Sıradaki bağlayıcı iş Faz 3 / İş 13'tür.**
>
> Daha önce: Faz 3 / **İş 10 TAMAMLANDI** — Dilim 4–8 ve belge turu
> `docs: record completed diagnostics and integrity work`. Saat geriye gidince
> yedekler kullanılabilir kalıyor (R12); onaylı ve geri alınmış içe aktarmaların
> 13 aday çelişkisi ölçüldü ve hepsi `L`'ye girdi (R14); geri alma C2–C4'ü
> bozuk bir batch'i `PROVENANCE_BROKEN` ile reddediyor; kayıtları çelişen bir
> yedek, onay sorusundan ve güvenlik yedeğinden önce `IMPORT_RECORDS_CONTRADICT`
> ile reddediliyor; hasarlı canlı veritabanı Room'dan önce salt okunur
> `quick_check` ile bulunup `DATABASE_DAMAGED` ile açılmıyor (R13). Room 8, PLAN
> ve bağımlılıklar değişmedi. Tam koşu 3701 / 0 / 0 / 0 (269 sınıf). Ayrıntı
> §25.4 "İş 10 / Dilim 4–8'de uygulanan hâli". **Sıradaki bağlayıcı iş Faz 3 /
> İş 11'dir.**
>
> Daha önce: İş 10 / Dilim 3 sonrası **stabilizasyon turu** —
> `test(desktop): make process and window checks deterministic`. Üretim kodu
> değişmedi. Tanılama testleri artık makinenin 500 ms'de kaç satır yazabildiğini
> ölçmüyor: işçi olayla (diske inen satır, kapıda tutulan yazma, alınan/reddedilen
> kilit) koordine ediliyor ve tam koşu prize bağlı değil (§25.4 "Stabilizasyon
> turu"). Masaüstü smoke'ta pencere yalnız `SafeWindowCloser` ile kapatılıyor:
> tam başlık + başlatılan süreç ağacı + kapatmadan hemen önce yeniden doğrulama
> (§30). Üç okuma yolunun `IllegalArgumentException` ve `Error` sınırları da ayrı
> testlerle çivilendi. Sıradaki bağlayıcı kod dilimi yine **İş 10 / Dilim 4**.
>
> Daha önce: Faz 3 / **İş 10 / Dilim 3** —
> `fix(errors): report storage and directory failures safely`.
> Dilim 2'nin **ölçtüğü** beş tipsiz kaçış tipli Türkçe sonuçlara çevrildi:
> taslak kaydı, oyun tablosu okuması, renk kataloğu okuması, havuz okuması ve
> açılış dizinleri (§25.4 "İş 10 / Dilim 3'te uygulanan hâli"). Yeni olay kodu
> eklenmedi; üç gözlenen okuma tek bir dar seam'den geçiyor ve yalnız
> `SQLiteException` cevaplanıyor — kusur ve cancellation aynen yükseliyor.
> Sıradaki bağlayıcı kod dilimi **İş 10 / Dilim 4** (R12: saat geriye gidince).
>
> Daha önce: İş 10 / Dilim 2 —
> `feat(diagnostics): record failures at their typed boundaries`.
> PLAN `14.7.2`'nin olay matrisi üretime bağlandı: her hata, tipli sonuca
> çevrildiği sınırda **bir kez** kaydediliyor; kullanıcıya gösterilen hiçbir metin,
> sonuç veya ekran durumu değişmedi (§25.4 "İş 10 / Dilim 2'de uygulanan hâli").
> Tipsiz kaçan sınırlar tahmin edilmedi, **ölçüldü**; Dilim 3'ün kapsamı o
> matristir. Room şeması, PLAN ve bağımlılıklar değişmedi. O günkü tam koşu:
> 3634 / 0 / 0 / 0 (261 sınıf).
>
> Daha önce: İş 10 / Dilim 1 (`feat(diagnostics): keep a bounded diagnostic log in
> the state directory`) kayıt dosyası sözleşmesini, güvenli yazıcıyı, sınırlı
> kuyruğu, kendi süreç kilidini (R15) ve 5 × 1 MiB rotation motorunu yazdı; o gün
> hiçbir üretim sınırı kayıt üretmiyordu (3579 / 0 / 0 / 0).
>
> Daha önce: İş 9 kapanışı ve İş 10 belge turu (`docs: define diagnostics and data
> integrity failure semantics`) İş 9'u makineden bağımsız ölçütlerle kapattı ve
> İş 10'un sözleşmesini PLAN `14.7`'ye yazdı.
>
> Daha önce: İş 8 (`fix(ui): keep every screen usable with keyboard and
> scaling`) bütün uygulamayı dört görünümde taradı ve üç kusuru düzeltti (§17);
> o turun üç commit'inden sonra tam koşu 3535 / 0 / 0 / 0 idi.
>
> Daha önce: İş 9 ölçüm dilimi (`test(performance): measure the application with
> a thousand tasks`) 1.203 görevde sabit sorgu yapısını ve süzgeç doğruluğunu
> ölçtü; süre eşiği kararı o gün açıktı ve bu belge turunda makineden bağımsız
> kabul ölçütleriyle kapandı (§29).
>
> Daha önce: İş 5 (`fix(export): report storage that will not answer instead of
> hanging`) CSV export sözleşmesini madde madde doğruladı ve depolama okuma
> hatasını tipli `COULD_NOT_READ` yaptı (§25 matris).
>
> Daha önce: İş 7 / Dilim 4 (`feat(import): let an unfinished import be
> continued or removed`) İş 7'yi tamamladı: `Devam eden içe aktarmalar` listesi
> tek toplu sağlık okumasıyla geçerli taslakları `Devam et` + `Kaldır` ile,
> kayıtları uyuşmayan taslakları ayrı bölümde yalnız `Kaldır` ile gösteriyor.
>
> Daha önce: İş 7 / Dilim 3 (`feat(import): recognise an unconfirmed import whose
> records contradict each other`) D1–D9'u gerçek restore hattıyla ölçüp onayı iki
> kapıyla kapattı; Dilim 2 (`feat(import): remove an unconfirmed import in one
> transaction`) tipli ve postcondition'lı kaldırma motorunu yazdı; Dilim 1
> (`test(recovery): prove an interrupted write leaves all or nothing`)
> kesintiye dayanıklılığı gerçek süreç öldürmesiyle ölçtü; İş 7'nin belge turu
> (`docs: define interrupted import recovery semantics`) sözleşmeyi yazdı.
>
> Bir önceki durum: Faz 3 / İş 4'ün **dördüncü ve son dilimi** (migration
> öncesi eşleşmiş ham `.db` + yürütülmüş `.json` seti ve açılış kapısı)
> tamamlandı. İş 2, İş 3 ve **İş 4 bütünüyle bitmiştir**. İş
> 3'ün bağlayıcı metni PLAN `14.4.1`–`14.4.6`, `12.16` ve
> `16.`'dadır; uygulanan hâli §25.1'dedir. **İş 4'ün** bağlayıcı metni PLAN
> `14.4.7`–`14.4.13`, `11.4.2`, `12.16` ve `16.`'dadır; kararların özeti ve
> uygulanan hâli §25.2'dedir.
>
> Bu dosyanın önceki sürümü çok daha eski bir repo durumunu (Room v3, canlı `Item`
> modeli, AP-9/AP-10 adımlandırması) güncel mimariymiş gibi anlatıyordu. O bilgiler
> artık **§36 TARİHSEL / ARTIK GEÇERLİ DEĞİL** bölümüne taşınmıştır ve rehber olarak
> kullanılmamalıdır.

---

# 0. GÜNCEL CHECKPOINT

Aşağıdaki değerler bu dosya commit edilmeden hemen önce repo üzerinde
doğrulanmıştır.

```text
branch                : main
başlangıç HEAD        : a6db6c7883241b18364f90f753feade345c39876
                        (docs: record completed diagnostics and integrity work)
HEAD (bu commit öncesi): a0d1aa6 — fix(packaging): read the window class only through the window helpers
bu commit             : docs: record Linux packaging decisions and results
bu turun commit'leri  : a942683 build(linux): package a self-contained application archive   İş 11
                        44b329d build(arch): package the application for Garuda and Arch Linux İş 12
                        a0d1aa6 fix(packaging): read the window class only through the       tam koşunun
                                window helpers                                               bulduğu hata
working tree          : her commit'te temiz
Room şema sürümü      : 8   (DEĞİŞMEDİ; migration yok, yeni üçüncü taraf bağımlılık yok)
şema dosyaları        : 1.json … 8.json  hepsi bayt bayt aynı
PLAN.md               : DEĞİŞMEDİ (180ff640…) — İş 11/12 maddeleri seçilen paket türlerini
                        zaten kapsıyor; kararlar burada (§25.5) kayıtlı
fixture               : sample-import.xlsx DEĞİŞMEDİ (314780a4…)
test durumu           : ./gradlew clean check --rerun-tasks (bellek sınırlı, §30; ayrılmış süreçle)
                        → BUILD SUCCESSFUL, 3701 / 0 / 0 / 0 (269 sınıf), 7 dk 32 sn
                        (paket denetimleri test sınıfı değil, ayrı Gradle görevleridir)
tam koşu geçmişi      : (1) 44b329d'de 3701 test, 1 başarısız: SafeWindowCloserTest'in yüzey testi
                        paket denetiminin doğrudan xprop çağırdığını yakaladı → a0d1aa6;
                        (2) a0d1aa6'da yukarıdaki satır
dar koşular           : AppInfoTest dahil sürümü kullanan sınıflar → 3 sınıf / 29 test;
                        SafeWindowCloserTest 13/13; desktopWindowSmoke PASSED; ktlint temiz
paket smoke'ları      : ./gradlew :app:verifyLinuxPackage → PACKAGE: PASSED (§25.5)
(tam koşudan sonra)     ./gradlew :app:verifyArchPackage  → PACKAGE: PASSED (§25.5)
çıktılar              : pnp-tracker-0.1.0-linux-x86_64.tar.gz   94.309.676 bayt
                          SHA-256 a66bbb88dea52969889b6e5555e55b679827cb151fae48d0094e8c43cd958716
                        pnp-tracker-0.1.0-1-x86_64.pkg.tar.zst  92.537.371 bayt (kurulu 181.025.904)
                          SHA-256 eb296a35f43f352ba1da02ff25abfd5985f56c84afd1af14e3c805f0abe870ea
                        (ikisi de ardışık üretimlerde bayt bayt aynı; bu makinede, bu araçlarla)
gerçek kullanıcı alanı: başlangıç ve bitişte yalnız hash/metadata — pnp.db, pnp.db.lck, backups,
                        config, ~/.local/state birebir aynı; /opt ve /usr/bin'e hiçbir şey
                        kurulmadı, sudo kullanılmadı
```

**Bu commit İş 11 ve İş 12'yi kapatan belge turudur.** Paket türleri, sürüm
kaynağı, runtime, belirlenimcilik, doğrulama ve bilinen sınırlar §25.5'tedir.
Sıradaki bağlayıcı iş **Faz 3 / İş 13**'tür (temiz Garuda ortamında kurulum,
açılış, veri dizini, güncelleme ve kaldırma testi — §33 R6: ayrı bir temiz
ortam gerektirir).

**Önceki belge commit'i (`a6db6c7`) İş 10'u kapatıyordu; İŞ 10 TAMAMLANDI.**
 Dilim 4–8'in
uygulanan hâli, R14 ölçüm matrisi ve R13 maliyet/tespit ölçümü §25.4 "İş 10 /
Dilim 4–8'de uygulanan hâli"ndedir. Sıradaki bağlayıcı iş **Faz 3 / İş 11**'dir
(paket türü, JRE, sürüm kaynağı R5 — kullanıcı kararı bekler).

**Önceki commit (`89dff7e`) bir stabilizasyon turuydu, dilim değildi.**
 İş 10 / Dilim 3'ün
doğrulama altyapısındaki iki kararsızlık kapandı — CPU hızına bağlı tanılama süreç
testi ve pencereyi kısmi başlıkla seçen smoke — ve üç okuma yolunun
`IllegalArgumentException`/`Error` sınırları testle çivilendi. Ayrıntı §25.4
"Stabilizasyon turu". Sıradaki bağlayıcı kod dilimi hâlâ İş 10 / Dilim 4'tür.

**Bir önceki commit (`62e36a0`) İş 10 / Dilim 3'tü:** Dilim 2'nin ölçtüğü beş
tipsiz kaçış tipli Türkçe sonuçlara çevrildi (§25.4 "İş 10 / Dilim 3'te uygulanan
hâli"). O günkü tam koşu 3660 / 0 / 0 / 0 (265 sınıf) ancak prizde alınabilmişti;
pildeki kırmızı koşu bu turun konusudur.

**Bir önceki commit (`e808104`) İş 10 / Dilim 2'ydi:** PLAN `14.7.2`'nin olay
matrisi üretime bağlandı ve tipsiz kaçışlar ölçüldü (§25.4).

**Ondan önceki commit (`ce29d8a`) İş 10 / Dilim 1'di:** kayıt dosyası sözleşmesi,
güvenli yazıcı, sınırlı kuyruk, süreç kilidi ve rotation (§25.4).

**Bir önceki commit (`85ccc32`) Faz 3 / İş 8'di; İŞ 8 TAMAMLANDI.** Envanter, tablo güdümlü tüm
uygulama taraması ve bulunan üç kusurun düzeltmesi §17 "Faz 3 / İş 8"dedir.

**Ondan önceki commit (`14490a8`) Faz 3 / İş 9'un ölçüm dilimiydi; bu belge turuyla İŞ 9 TAMAMLANDI.**
1.203 görevlik, uygulamanın kendi yazma yollarıyla kurulan kütüphane gerçek
`StartupGate` ile açılıyor; açılış, tekrar okuma, arama ve havuz süzgeçleri
makineden bağımsız koşullarla doğrulanıyor, süre/bellek yalnız kayıt (§29).

**Bir önceki commit (`d1c76c6`) Faz 3 / İş 5'ti; İŞ 5 TAMAMLANDI.** CSV export sözleşmesinin her
maddesi gerçek üretim yolunu kullanan bir testle eşleşiyor (§25 matris). Testin
bulduğu tek üretim kusuru düzeltildi: `TaskExportStore` depolama hatasını ham
bırakıyordu ve export ekranı meşgul kalıyordu; artık `COULD_NOT_READ`.

**Bir önceki commit (`557122e`) Faz 3 / İş 7'nin dördüncü ve son dilimiydi; İŞ 7 TAMAMLANDI.**
Kullanıcı artık onaylanmamış bir içe aktarmaya kaynak dosya olmadan devam
edebiliyor ya da açık onayla kaldırabiliyor. Liste `ImportDao.healthOfDraftBatches`
ile (tek transaction, taslak sayısından bağımsız üç okuma; tekil
`draftHealthOf` ile aynı saf fonksiyon, eşdeğerlik dokuz çelişkinin her biriyle
testli) okunuyor ve D1–D9'un okuduğu altı tablonun herhangi biri değişince Room
invalidation tracker'ı üzerinden yeniden okunuyor. Kayıtları uyuşmayan taslaklar
ayrı bölümde, Türkçe uyarıyla ve yalnız `Kaldır` ile gösteriliyor. `Devam et`
her basışta taslağı yeniden sınıflandırıyor; bayat bir liste inceleme ekranına
bir şey sokamıyor. Kaldırma sonucu (başarı, zaten kaldırılmış, taslak değil,
gerçek kayıtlar bağlı, kaydedilemedi) motorun kendi cevabından çiziliyor.
Ayrıntı §25.3 "İş 7 / Dilim 4'te uygulanan hâli".

**Bir önceki commit (`19a9152`) İş 7'nin üçüncü dilimiydi: bozuk DRAFT
sınıflandırması ve onay kapısı.** Önce ölçüm yapıldı: sağlıklı bir veritabanından kanonik bir yedek
okundu, D1–D9'dan **yalnız biri** eklendi, `dataSha256` biçimin kendi
fonksiyonuyla yeniden hesaplandı ve belge gerçek `UntrustedBackupReader` +
gerçek `TemporaryBackupProbe` + gerçek `LiveBackupRestorer` hattından geçirildi.
**Dokuzun dokuzu da canlı Room 8 veritabanına ulaştı** (D3 iki yönde, D6/D7
silinmiş satırla da, D9 hem `PENDING` hem hedef oyunlu `ACCEPTED` ile); her
birinde canlı DB yüklenen belgeye değer düzeyinde eşit, `foreign_key_check` boş,
`integrity_check` ok. Belgelenen liste doğru çıktı, PLAN değişmedi. Sonra salt
okunur `ImportDao.draftHealthOf` (dört tipli sonuç: `Sound`, `Contradicting` +
iç neden kümesi, `NotFound`, `NotADraft`) — tek transaction'da, blok sayısından
bağımsız **üç okuma**, D8 Kotlin'de UTF-16 uzunluğuyla. Onay yolu iki kapı
kazandı: snapshot'tan **önce** salt okunur ön kontrol (bozuksa snapshot, rotation
ve yazma YOK) ve `BEGIN IMMEDIATE` transaction'ının içinde 15 tablo
karşılaştırmasından sonra, ilk domain yazımından önce yeniden kontrol. Sonuç yeni
tipli `ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER`; kullanıcı yalnız
onay denediğinde tek bir Türkçe cümle görür. Arayüzde düğme, liste veya bölüm
yok (Dilim 4). Ayrıntı §25.3 "İş 7 / Dilim 3'te uygulanan hâli".

**Ondan önceki commit (`18db43a`) İş 7'nin ikinci dilimiydi: taslağı kaldırma
motoru.** Onaylanmamış bir içe aktarma artık tek bir `BEGIN IMMEDIATE` transaction'ında,
kararları o transaction'ın içinde yeniden okunarak kaldırılıyor. Beş tipli
sonuç var (`Removed` + dört `Refused`: `ALREADY_REMOVED`, `NOT_A_DRAFT`,
`HELD_BY_RECORDS`, `COULD_NOT_SAVE`); ikinci çağrı exception değil
`ALREADY_REMOVED`; `D6`/`D7` başvurusu FK hatası beklenmeden açık bir SELECT ile
bulunuyor; postcondition 15 tablonun sayımını önce/sonra karşılaştırıyor ve
bozulursa `IllegalStateException` ile bütün transaction geri alınıyor. Maliyet
taslak boyutundan bağımsız: 0, 1 ve 42 hücreli taslak aynı altı ifadeyi
çalıştırıyor (ölçüldü). Arayüz yok; `ImportDraftRemovalStore` henüz `Main`'e
bağlı değil (kabul edilmiş kalıp, §33 R3). Ayrıntı §25.3 "İş 7 / Dilim 2'de
uygulanan hâli".

**`5a1cc9b` İş 7'nin birinci dilimiydi ve yalnız testti.** Kararın
dayandığı cümle — "her yazma tek transaction'dır; SQLite ya hepsini ya hiçbirini
tutar" — artık bir inanç değil, **gerçek bir ikinci JVM process'inin öldürülmesiyle
ölçülmüş** bir sonuçtur. Dört yazma yolu (taslak kaydı, taslak düzenleme, onay,
geri alma) transaction'ın ortasında ve commit'ten hemen sonra SIGKILL ile
kesildi; yeniden açılış her seferinde açılış kapısından geçti, `integrity_check`
`ok`, `foreign_key_check` boş döndü ve 15 tablonun parmak izi ya işlemden
önceki ya da commit edilen değere **birebir** eşit çıktı. Kalıcılık ayarı
ölçüldü: **`journal_mode = wal`, `synchronous = 1 (NORMAL)`**, iki bağlantı
türünde de ve yeniden açılıştan sonra da. Ayrıntı §25.3 "İş 7 / Dilim 1'de
uygulanan hâli".

Belge turunun denetim sonucu: uygulamanın kendi yazma yolları ve beklenmeyen
kapanış bozuk bir `DRAFT` üretemez; doğrulanmış JSON geri yüklemesi ise yaşam
döngüsü kurallarını denetlemediği için dokuz kesin predicate'le (`D1`–`D9`)
sınırlı tutarsızlıkları canlı DB'ye taşıyabilir. Bu cümle artık kod okuması
değil, **Dilim 3'te gerçek hatla ölçülmüş** bir sonuçtur (R14 hâlâ açıktır).

## Bir önceki commit: İş 4 / Dilim 4 — İŞ 4 TAMAMLANDI

**`6119b1f` Faz 3 / İş 4'ün dördüncü ve son dilimidir; İŞ 4 TAMAMLANDI.**
Uygulama artık kullanıcının veritabanını **yalnız açılış kapısından** açıyor:
instance kilidi alınıyor, `user_version` Room açılmadan okunuyor, ve eski bir
şema görülürse gerçek migration başlamadan önce **iki eşleşmiş artefakt** —
migration kodunun hiç çalışmadığı ham `.db` klonu ve bu klonun ayrı bir çalışma
kopyasının gerçek zincirle v8'e yürütülmesinden üretilen doğrulanmış `.json` —
yazılıp kanıtlanıyor.

**Üç tetikleyicinin üçü de bağlandı.** Otomatik yedeğin sahipleri artık şunlar ve
başkası yok:

```text
restore öncesi   RestoreController          → pnp-oncesi-*.json
import onayı     ImportConfirmationStore    → pnp-otomatik-import-*.json
açılış/migration StartupGate                → pnp-otomatik-migration-*.db + .json
```

`DatabaseFactory` hâlâ ne housekeeping ne snapshot alıcı alıyor — ve bu artık
yalnız temizlik değil, **taşıyıcı bir kural**: kapı, seti üretirken çalışma
kopyasını bu fabrikadan geçiriyor; kendi başına snapshot alan bir fabrika,
kopyanın snapshot'ını alırdı.

Açılışın sırası (PLAN `14.4.10`, birebir):

```text
 1  instance kilidi alınır       → alınamazsa DB HİÇ açılmaz, hata ekranı
 2  user_version Room AÇILMADAN okunur (salt okunur SQLite bağlantısı)
 3  dosya yok / boş  → normal oluşturma yolu, snapshot YOK
 4  sürüm 8          → snapshot YOK, normal açılış
 5  sürüm 1..7       → ÖNCE set
 6  sürüm > 8 / okunamaz → DB AÇILMAZ, hata ekranı
 7  iki ad AYNI ANDA sahiplenilir (aynı sonek)
 8  ham klon: salt okunur VACUUM INTO → .part → atomik move
    kanıt: SQLite başlığı + user_version = addaki v<eski> + integrity_check +
           foreign_key_check + kaynakla AYNI tablolar ve AYNI satır sayıları
 9  klonun AYRI çalışma kopyası geçici dizinde gerçek zincirle v8'e yürütülür
10  yürütülmüş kopyadan kanonik belge üretilir ve atomik yazılır
11  belge GERÇEK UntrustedBackupReader + TemporaryBackupProbe ile geri okunur
12  geri okunan, çalışma kopyasının TAZE okumasıyla karşılaştırılır
13  ikisi de kanıtlanmadan MigrationSnapshotSet YOKTUR → gerçek DB açılamaz
14  set kanıtlandıktan sonra automaticBackupCount ile rotation (fail open)
15  gerçek DB normal zincirle açılır; burada düşerse set KORUNUR + hata ekranı
16  kilit 1'den 15'in sonuna kadar tutulur, her çıkışta bırakılır
17  çalışma dizini, -wal, -shm ve .part her yolda silinir
```

İçe aktarma onayının sırası:

```text
İçe Aktarma → taslaklar hazır → `Onayla ve görevleri oluştur`
→ onay penceresi, otomatik yedeğin alınacağını TEK cümleyle söyler
→ [Onayla]  ← ikinci basış bu noktadan sonra hiçbir şey yapmaz
→ 15 tablo okunur, kanonik belge üretilir                  ← DB'ye 0 bayt
→ backups/ altına pnp-otomatik-import-* atomik yazılır
→ dosya GERÇEK okuyucuyla geri okunur; checksum ve satırlar karşılaştırılır
→ ayardaki sayı okunur, rotation uygulanır (hata onayı engellemez)
→ TEK transaction: 15 tablo yeniden okunur ve snapshot'la karşılaştırılır
→ farklıysa hiçbir domain yazımı yapılmadan reddedilir
→ aynıysa mevcut confirmDraftBatch aynı transaction içinde sürer
```

Geri yüklemenin kullanıcıya görünen akışı ve sırası:

```text
Ayarlar → Yedekten geri yükle → .json seçimi
→ Dilim 3'ün bütün kapıları (boyut, UTF-8, JSON, zarf, checksum, değer,
  graf, geçici Room v8 denemesi)                    ← buraya kadar DB'ye 0 bayt
→ güvenli özet + açık replace uyarısı + onay        ← odak `Vazgeç` üzerinde
→ backups/ altına zorunlu güvenlik yedeği (atomik)
→ canlı DB'de TEK replace transaction
→ commit sonrası yeniden okuma ve hash doğrulaması
→ Flow'lar tazelenir, açık düzenleme yüzeyleri kapanır, yeniden başlatma YOK
```

Transaction'ın içi, sırasıyla:

```text
1  15 tablo okunur ve güvenlik yedeğinin anlık görüntüsüyle karşılaştırılır
2  farklıysa hiçbir DELETE/INSERT yapılmadan reddedilir
3  PRAGMA defer_foreign_keys = TRUE
4  15 tablo TERS yabancı anahtar sırasıyla temizlenir (12 tohum renk dâhil)
5  yedeğin satırları PLAN 14.4.2 sırasıyla yazılır
6  açık PRAGMA foreign_key_check
7  15 tablo yeniden okunur ve yedekle karşılaştırılır
8  ancak ikisi de tuttuysa commit
```

`BEGIN IMMEDIATE` ile yazma kilidi ilk okumadan önce alınır ve Room tek writer
bağlantısı tuttuğu için commit'ten commit sonrası doğrulamaya kadar başka hiçbir
yazma araya giremez. Bu yüzden commit sonrası okuma, restore hakkında bir ifade
olarak kalır.

`ValidatedBackup` ve `SafetySnapshot` dışında hiçbir şey `LiveBackupRestorer`'a
verilemez; ikisinin de üretim yapıcısı yoktur. Dosya, yol, ham DTO veya
ayrıştırılmış belge bu API'ye giremez.

Şema **değişmemiştir**; yeni bağımlılık **eklenmemiştir**.

`PLAN.md` İş 4 / Dilim 4'te değiştirilmemişti; bu belge turunda İş 7 için
değişti.

## Doğrulama hash'leri

```text
PLAN.md  180ff640aa7782539ecc2c423b01adb27fc3c662849e7972eaa1f4ad31bbfee2
         (İş 10 / Dilim 1 PLAN'ı DEĞİŞTİRMEDİ; değer İş 9 kapanışı + İş 10 belge turu — docs: define diagnostics and data
          integrity failure semantics. Bu turdan önceki değer
          a266824ba28e1b90b3f650575905587951b5309debdfc62a56f7d2bdfbadff04
          (İş 7 belge turundan İş 8'e kadar değişmemişti); ondan önceki
          db891ba8362bb5ee837535aa042b8414ac2062d97a8fa25bff744884b09a3455)

1.json   7cafd48fb4b06ec1da00b3f15f4335aae46fb8b40fc57926cde442dda515a724
2.json   e596d1bccc5054bf4442faff43ebdfff03ff4c5024d4afc1d8e9ddad2ed3f11a
3.json   5acd37f74b2898ce6f0577149ae0dc609be8ca141292b69b29b5157eb14f5da0
4.json   1b1c5613b5eab3236de96abb1083a9256b39b0930ec5a9e520cf4777670219a0
5.json   e504a0654d3db06b2be353b8fbbcdb6dfed2230f4c53e99c9280ee8f0f99d017
6.json   aa73e89137f4b5585e4ed0c0e7bbab38aad841897cd2ce16b910c4c4cc4e2277
7.json   690843ebbfe4d61b33bf7db2e35b3a5038ba0206dc6b4ca5484311312af298b7
8.json   498dfef21e479209c793f731b3593dae37cf688ae7bf275775eb744255913480
         (Faz 3 / İş 2 dilim 1'de eklendi; Room'un kendi ürettiği şema)

anonim fixture (sample-import.xlsx)
         314780a48e5002b2ffaef6856c63d2e42a55759d39ac485b8f00753f551d1833
```

## Gerçek kullanıcı veritabanı

Yol: `$XDG_DATA_HOME/pnp-tracker/pnp.db`

Bu dosyanın hash'i, boyutu ve mtime'ı **bilinçli olarak buraya yazılmamıştır**:
bu belge repoya commit edilir ve depo açık kaynak yayınlanacaktır; kişisel bir
veritabanının parmak izi orada durmamalıdır. Bunun yerine kural şudur:

> Her dilimin **başında** ve **sonunda** gerçek DB'nin SHA-256'sı, boyutu ve mtime'ı
> ölçülür ve karşılaştırılır. Değişmişse, değişiklik yapmadan durulur ve raporlanır.

Gerçek DB hiçbir aşamada açılmaz, kopyalanmaz veya migrate edilmez. Bütün testler ve
manuel turlar geçici Room veritabanları ve geçici XDG dizinleri kullanır. Bu koruma
`assertRealApplicationDatabaseUntouched` yardımcı fonksiyonuyla **113 test sınıfında**
uygulanmaktadır. (Sayı İş 10 / Dilim 3'te düzeltildi: belgede 103 yazıyordu, gerçek
değer o commit'ten önce zaten 109'du; bu dilim üç sınıf ekledi.) Sayı tek bir yerde tutulur; §29 aynı değeri anar ve tarama
`grep -rl 'assertRealApplicationDatabaseUntouched' app/src/*Test` ile yapılır.

## Yaklaşan migration hakkında soyut gözlem  *(İş 4 için belirleyici)*

Gerçek veritabanının **içeriği açılmadan**, yalnız dosya başlığı ve şema metni
salt okunur incelenerek şu gözlenmiştir:

```text
user_version                 3        (kodun şema sürümü 8'dir)
tablolar                     v3 şemasının tabloları; `items` dâhil
domain/import tablolarında   kullanıcı verisi GÖRÜNMÜYOR; yalnız 12 tohum renge
                             karşılık gelen kayıt izi var
-wal / -shm                  yok
```

Bundan çıkan ve **kayda değer** olan tek şey şudur:

- Uygulamanın bir sonraki normal açılışı, büyük olasılıkla **v3 → v8** bir
  migration zincirini tetikleyecektir. Bu, İş 4'ün migration snapshot'ının ilk
  gerçek tetiklenmesi olacaktır ve varsayımsal değildir.
- `Migration3To4`, üretim satırı taşıyan bir v3 veritabanını **bilerek reddeder**
  (bkz. kendi belgesi). Yalnız renk taşıyan bir veritabanı temiz göç eder.

**Bu gözlem bir garanti değildir.** Veritabanı açılmadığı için "kullanıcı verisi
yok" kesin olarak bilinemez; yalnızca dosyada görünmediği söylenebilir. Plan ve
uygulama, veri **varmış gibi** güvenli olmak zorundadır.

> **O geçici kural KALKTI ve yerini koda bıraktı.** Dilim 4 bitene kadar kural
> şuydu: gerçek uygulama normal XDG diziniyle açılmamalı, çünkü bir açılış
> migration'ı snapshot'sız çalıştırırdı. Artık çalıştıramaz — `StartupGate`
> eski bir şema gördüğünde, doğrulanmış bir `MigrationSnapshotSet` elde
> edilmeden Room'a hiç ulaşmaz, ve o tip yalnız iki eş de yazılıp geri
> okunduktan sonra üretilebilir. Yani koruma bir hatırlatma olmaktan çıkıp
> **açılış yolunun bir özelliği** oldu.
>
> **Buna rağmen gerçek kullanıcı veritabanı bu geliştirme turunda AÇILMADI ve
> MIGRATE EDİLMEDİ.** Kullanıcının ilk gerçek migration'ını tetiklemek bir
> geliştirme adımı değil, kullanıcının kendi kararıdır; tur boyunca dosya yalnız
> açılmadan ölçüldü (hash, boyut, mtime) ve değişmediği doğrulandı. Uygulama ilk
> kez normal XDG ile açıldığında kapı çalışacak ve v3 → v8 geçişinin önüne
> eşleşmiş seti koyacaktır.

---

# 1. PROJE ÖZETİ

PNP, masaüstü öncelikli bir print-and-play / masa oyunu üretim takip uygulamasıdır.

Temel amaçlar:

- Oyunları ve oyun içindeki üretim işlerini takip etmek.
- Baskı gerektiren parçaları görevler üzerinden takip etmek.
- Renk bazlı 3D baskı takibini kolaylaştırmak.
- Kart ve mukavva üretim hatlarını ayrı havuzlarda göstermek.
- Excel/XLSX ve CSV dosyalarından mevcut veriyi kontrollü biçimde içeri almak.
- Uygulamayı **offline-first** ve **Linux-first** çalıştırmak.
- İleride masaüstü + Android arasında paylaşılabilir bir mimari kurmak.

Veri senkronizasyonu ilk sürümün parçası değildir.

## Temel prensip

Uygulama "aynı verinin farklı görünümleri" mantığıyla çalışır. Havuzlar yeni `Task`
üretmez veya kopyalamaz; mevcut kalıcı `Task` kayıtlarının salt okunur
projection'larıdır.

---

# 2. KANONİK KAYNAK

**PLAN.md projenin tek yetkili kaynak dokümanıdır.**

Bu dosya ile PLAN.md arasında çelişki görülürse:

1. PLAN.md'yi esas al.
2. Çelişkiyi raporla.
3. Kapsam dışı davranış ekleme.
4. Eski sohbetten "hatırlanan" bir davranışı kendiliğinden geri getirme.

PLAN.md'nin bu bağlamda en sık başvurulan bölümleri:

```text
5.4 / 5.5 / 5.13   Game → GameCell → CellSegment → Task zinciri, Item'ın kaldırılması
5.12               TaskStage ve ProgressEvent
6                  3D baskı ilerleme modeli
7 / 8              Kart ve mukavva hatları
11.7               Bilinmeyen adet / sınıflandırılmamış iş
12.1               Gezinme hedefleri
12.15              Geçmiş ekranının göstermesi gerekenler
13                 Arama, filtreleme, sıralama
14.2 / 14.3 / 14.4 Yerel veri, Excel/CSV, yedekleme ve dışa aktarma
16                 Veri bütünlüğü ve performans invariant'ları
17                 Erişilebilirlik
18                 Üç geliştirme fazı
23                 Nihai Definition of Done
```

Her dilimin başında repo/branch/HEAD/çalışma ağacı ve PLAN.md durumu doğrulanır.

---

# 3. GELİŞTİRME METODOLOJİSİ

> **Önemli:** Eskiden kullanılan "AP-1 … AP-10" adımlandırması **artık kullanılmıyor**
> (bkz. §36). PLAN.md AP'lerden söz etmez. Çalışma birimi **faz** ve o fazın
> **numaralı işleri**dir; her iş küçük, test edilebilir dikey dilimlere ayrılır.

Genel kural:

- Bir dilimin kapsamı dışına çıkma.
- Sonraki işe/dilime kendiliğinden geçme.
- Beklenmeyen repo durumunda değişiklik yapmadan dur ve farkı raporla.
- Her dilim kendi otomatik testleri, körlük probları ve geçici XDG dizinleriyle
  yapılan manuel turuyla birlikte teslim edilir.
- Her dilim sonunda test + smoke + DB invariant + git durumu raporla.
- Başarılı dilim sonunda tek atomik commit oluştur.
- Kullanıcı "sonraki talimatımı bekle" dediyse devam etme.

---

# 4. TEKNOLOJİ YIĞINI

Sürümler `gradle/libs.versions.toml` içinde sabitlenmiştir ve ilgili dilim açıkça
istemedikçe değiştirilmez:

```text
Kotlin                2.4.10
Compose Multiplatform 1.11.1
Room KMP (androidx.room3) 3.0.1
androidx.sqlite       2.7.0  (bundled driver)
KSP                   2.3.11
kotlinx-coroutines    1.11.0
kotlinx-serialization 1.11.0  (yalnız commonMain; yedek belgesinin JSON'u)
Apache POI            5.5.1
ktlint-gradle         14.2.0
Gradle wrapper        8.13
```

Yapı: tek modül (`app`), Kotlin Multiplatform, JVM hedefi `desktop`.
Kaynak setleri: `commonMain`, `commonTest`, `desktopMain`, `desktopTest`.

- Ortak domain/veri katmanı `commonMain` altındadır.
- Linux dosya sistemi, XLSX okuma, AWT dosya diyalogları ve paketleme kodu
  `desktopMain` altındadır. **POI yalnızca desktop katmanında kullanılır.**
- Basit constructor tabanlı dependency injection; ağır DI framework'ü yoktur.
- Yeni bağımlılık, PLAN veya ilgili dilim açıkça izin vermedikçe eklenmez.

## Verilmiş ve kullanılmış bağımlılık izni

`kotlinx-serialization-json` ve gerektirdiği Kotlin serialization derleyici
eklentisi **Faz 3 / İş 3 için açıkça izinlidir** (PLAN `14.1`, `14.4.1`).
Gerekçe: standart kütüphanede JSON yoktur ve güvenilmeyen bir dosyayı ayrıştırmak
elle yazılacak bir ayrıştırıcıya bırakılmayacak kadar geniş bir yüzeydir
(unicode kaçışları, surrogate çiftleri, yuvalama derinliği).

**Dilim 1'de eklenmiştir:**

```text
kütüphane   org.jetbrains.kotlinx:kotlinx-serialization-json  1.11.0
            (yazıldığı gün en güncel KARARLI sürüm; RC alınmadı)
kapsam      yalnız commonMain
eklenti     org.jetbrains.kotlin.plugin.serialization
            version.ref = "kotlin"  → sürümü Kotlin ile aynı kaynaktan gelir
çözüm       kotlinx-serialization-json-jvm 1.11.0 + core 1.11.0, BOM hizalı
```

Compose'un getirdiği transitif `kotlinx-serialization-core` **1.7.3'ten 1.11.0'a
yükseldi**; bu, Gradle'ın kendi sürüm birleştirmesidir ve başka hiçbir bağımlılık
sürümü değişmemiştir. İzin başka hiçbir bağımlılığa genişletilmez.

## Kimlik ve zaman tipleri — tek istisnalı kural

Üretim kodunda `java.util.UUID` ve `java.time` **kullanılmaz**; kimlik `EntityId`
(`kotlin.uuid.Uuid`), zaman `kotlin.time.Instant`'tır. Kuralı
`ProductionSourceTypeUsageTest` kaynak ağacını tarayarak uygular.

**Tek adlandırılmış istisna:** `desktopMain/.../domain/time/DesktopLocalMoment.kt`.
Saklanan bir anı kullanıcının kendi takviminde okumak (zaman dilimi, yaz saati
geçmişi) hiçbir Kotlin stdlib tipinin yapamadığı bir iştir ve PLAN 14.1 bunun için
eklenecek bir tarih kütüphanesi saymaz — bu yüzden platformun takvimi, yalnız bu
`actual` içinde sorulur. Saklanan, taşınan ve karşılaştırılan tip `Instant` olarak
kalır; dönen şey gösterilip atılan birkaç tam sayıdır (`LocalMoment`). İkinci bir
dosya aynı izni isterse test yine kırılır; istisnanın adla verilmesinin sebebi budur.

## Bilinen ve kusur olmayan uyarılar

```text
"Log4j API could not find a logging provider."   → POI'nin bilgi mesajı
Gradle 8.13 forward-compatibility uyarısı        → bastırılmaz, wrapper değiştirilmez
expect/actual classes are in Beta                → Room KMP üretimi
GameTableController.kt: 'when' is exhaustive so 'else' is redundant
                                                 → kozmetik, Faz 2'den beri var
```

## İleride düşünülen senkronizasyon

FastAPI + PostgreSQL + self-hostable server ileride değerlendirilebilir. İlk üç fazın
parçası değildir; kullanılmayan backend soyutlamaları şimdiden yazılmaz.

---

# 5. VERİ MODELİ — GÜNCEL

## Zincir

```text
Game → GameCell → CellSegment → Task
```

- **Game** — bir oyun satırı.
- **GameCell** — o oyunun bir sütunundaki hücre (`CellColumnType`).
- **CellSegment** — hücrenin belgesindeki sıralı bir parça: düz metin ya da bir görev.
- **Task** — bir üretim işi. Havuzların temel veri birimidir.

Bir hücrenin parçaları her zaman **sıralı, boşluksuz ve örtüşmesizdir**; yan yana düz
metin parçaları birleştirilir. Bir `Task` tam olarak bir `TaskSegment`'e aittir;
çapasız görev veya iki parçaya bağlı görev oluşamaz.

## Kaldırılmış kavramlar

- **`Item` (öge) yoktur.** `items` tablosu şema v4'te düşürülmüştür; `tasks.item_id`
  zorunluluğu aynı dilimde kalkmıştır. Tabloyu tanıyan tek yer, onu düşüren
  migration'dır (PLAN 5.13).
- **Component / partName / ayrı parça sayacı modeli yoktur.**
- **Oyun ve renk arşivi yoktur.**
- **`ALTERNATIVE` renk ilişkisi yoktur**; `task_colors` yalnız sıralı `REQUIRED`
  ilişkileri taşır.

## Şema v8 varlıkları

```text
games              game_cells         cell_segments
colors             color_aliases
tasks              task_colors        task_stages
progress_events    history_events
import_batches     raw_import_blocks   import_batch_cells
draft_tasks        draft_task_colors
```

`import_batch_cells` v8'de eklendi: bir içe aktarmanın yazdığı her hücrenin, o
yazımdan **önceki** tam metni. Ayrıntı §19 ve §33 R1'de; kural PLAN `11.4.4`'te.

## Enum'lar (güncel değerler)

```text
PoolType            THREE_D CARD BOARD SPECIAL
CellColumnType      THREE_D CARD BOARD SPECIAL NOTES
TrackingMode        THREE_D_BATCH PIPELINE CHECKLIST COUNTED
ProductionStage     PRINT LAMINATE GLUE CUT
SegmentKind         PLAIN_TEXT TASK
SourceColumnType    GAME THREE_D CARD BOARD SPECIAL MISSING BORROWED
ImportSourceFormat  XLSX CSV
ImportBatchStatus   DRAFT CONFIRMED ROLLED_BACK
HintDecision        NONE PENDING ACCEPTED REJECTED
ProgressEventKind   FAILURE_REPORTED SHORTAGE_RESOLVED
HistoryEventKind    TASK_STAGE_QUANTITY_CHANGED TASK_COMPLETED TASK_REOPENED
                    TASK_DELETED TASK_RESTORED TASK_CONVERTED_TO_TEXT
                    GAME_DELETED GAME_RESTORED
                    IMPORT_CONFIRMED IMPORT_ROLLED_BACK TASK_ROLLED_BACK
```

Son üç `HistoryEventKind` değeri Faz 3 / İş 2 dilim 2 ile eklendi. Enum TEXT
olarak saklandığı için şema sürümü değişmedi (§15).

---

# 6. RENK VE TASK SEMANTİĞİ

Aynı isimli iş birden fazla renkte üretilecekse iki farklı anlam vardır:

### Seçenek A — çoklu görev (ayrı varyantlar)

Her renk ayrı üretilecekse renk başına adet sorulur ve **her renk için ayrı Task**
oluşturulur. Bu görevler tamamen bağımsızdır: aralarında üst görev, grup veya ortak
sayaç yoktur.

### Seçenek B — tek öge çok renk

Renkler aynı fiziksel modelde birlikte kullanılacaksa **tek toplam adet** sorulur ve
**tek çok renkli Task** oluşturulur.

## Quantity nerede tutulur?

Quantity **renk başına değil Task başına** aittir.

```text
Task.requiredQuantity = 10
Task.colors = [Gri, Siyah]
```

Bu 10 değeri her renk için ayrı 10 değildir.

---

# 7. ÇOK RENKLİ TASK KURALI

```text
2+ renk = tek öge çok renk
1 renk  = tek renk
0 renk  = "Renk seçilecek"
```

Bir görevin **kaç renge sahip olduğu bir ile birden çoğu arasında geçiş yapamaz**:
PLAN 5.10 iki türü de tanımlar ama aralarında taşımayı tanımlamaz, dolayısıyla cevabı
uydurmak gerekirdi. Renksiz bir görevin ilk rengini alması ve tek renkli bir görevin
tek rengini kaybetmesi bu geçiş değildir; PLAN 5.10 renksiz görevi olağan bir durum
sayar ve 5.9 renk silerek böyle bir görev üretir.

Renderer farklı renk sayılarına toleranslı olmalıdır.

---

# 8. HAVUZLAR

Sabit dört havuz:

```text
1. 3D Baskı
2. Kartlar
3. Mukavva
4. Özel
```

## Havuzlar yeni kayıt üretmez

Havuzlar yeni Task oluşturmaz, Task kopyalamaz, açıldığında DB'ye yazmaz.
Salt okunur projection'dır.

## Task havuza nasıl girer?

Havuz üyeliği yalnızca `tasks.pool_type` üzerinden belirlenir.
**Tracking mode'dan havuz türetilmez.**

Aktif havuz üyeliği koşulları:

- doğru `pool_type`,
- Task silinmemiş,
- Game silinmemiş,
- Task tamamlanmamış,
- geçerli `TASK CellSegment → GameCell → Game` bağlantısı.

### Tamamlanmış Game

Tamamlanmış bir oyunun içindeki tamamlanmamış görev aktif havuzdan otomatik düşmez.

### Tamamlanmış Task

Tamamlanan görev bütün aktif havuz projection'larından çıkar; hücrede tikli ve üstü
çizili kalır, oyun kaydında ve geçmişte durur.

### Çapasız / segmentsiz Task

Havuzlarda gösterilmez, otomatik onarılmaz. Uygulamanın hiçbir üretim yolu böyle bir
görev üretmez.

### Özel havuz

Hiç silinmemiş özel görev yokken görünmez.

---

# 9. GEZİNME

PLAN 12.1'in tanımladığı hedefler ile **bugün gerçekten var olanlar** farklıdır.

## Bugün uygulanmış olanlar

```text
Ana Sayfa
Oyunlar            (Oyun Tablosu)
3D Baskı
Kartlar
Mukavva
Özel               (koşullu)
İçe Aktarma
Geçmiş             (PLAN 12.1 satır 798, PLAN 12.15)
Renkler
Ayarlar            (PLAN 12.16; İş 3 / Dilim 2'de açıldı, Dilim 4'te tamamlandı)
```

Sıra PLAN 12.1'in sırasıdır. `Geçmiş` koşulsuzdur: içi boşken de sidebar'dadır ve
boşluğunu ekranda söyler — `Özel` havuzun gizlenme kuralı ona uygulanmaz.
`Ayarlar` sırada sondadır ve PLAN 12.16'nın saydığı iki eylemi taşır: `Yedek
oluştur` ve `Yedekten geri yükle`. İş 4 / Dilim 2 buraya **tek** bir ayar
ekledi — saklanacak otomatik yedek sayısı (`1..50`, varsayılan `7`, `0`
geçersiz); başka ayar alanı eklenmez (§25.2).

## PLAN'da tanımlı, henüz yapılmamış olanlar

```text
(yok — PLAN 12.1'in bütün hedefleri açıldı)
```

### Placeholder kuralı

Henüz uygulanmamış ekranlar için **sahte/boş placeholder eklenmez.** Bir hedef ancak
arkasında çalışan bir ekran varken sidebar'a girer.

Ayrı bir oyun listesi ekranı ve ayrı bir oyun detay ekranı **yoktur**; görev oluşturma
ve düzenleme oyun tablosunun hücrelerinde yapılır.

---

# 10. 3D BASKI HAVUZU

Görünüm üç bölüme ayrılır:

```text
Renk seçilecek
Tek renkli
Tek öge çok renk
```

Tek renkli bölümünde görevler renk gruplarında listelenir. Renk gruplarının sırası:

1. `Color.sortOrder`
2. `Color.id`

Tek öge çok renk görevi her ilgili renk projection'ında görünebilir; **veri olarak
çoğaltılmaz**, aynı Task kimliğiyle görünür.

---

# 11. RENK SIRALAMA

- Renkler `sortOrder` ile sıralanır.
- Aynı `sortOrder` durumunda `id` stabil ikinci anahtardır.
- Renk yeniden adlandırılırsa `sortOrder` değişmez.
- Geri yüklenen temel renk kendi temel `sortOrder`'ına döner.
- `task_colors.slot_index` her zaman `0…N-1`, boşluksuz ve benzersizdir.

Renk, tombstone kuralının açık istisnasıdır: renk kullanıcı onayıyla **fiziksel
olarak** silinir. Renk silmek hiçbir görevi veya oyunu silmez; ilgili `TaskColor`
bağlantıları kalkar, kalan `slot_index` değerleri sıkıştırılır ve rengi kalmayan
görev `Renk seçilecek` bölümüne girer.

---

# 12. HAVUZ GÖREV SATIRI

Gösterilebilecek temel bilgiler:

- Oyun adı
- Görev adı
- Gerekli adet
- Ana baskı durumu
- Mevcut eksik miktar
- Toplam `FAILURE_REPORTED` miktarı
- Görev popover / eylemleri

Notlar ana görev kartında gösterilmez.
Kart/mukavva tarafında aşama rozeti kullanılır.

---

# 13. GRUP TOPLAMI INVARIANT'LARI

Grup toplamları **Task ID üzerinden distinct** hesaplanır.

- Aynı Task birden fazla renk grubunda görünüyorsa her gruba bir kez katkı verir.
- Aynı Task aynı renk grubunda iki kez görünmez.
- `×10` bir görev için 10 katkı verir, 30 değil.
- JOIN'ler görev miktarını çoğaltmaz.
- `SUM(DISTINCT quantity)` kullanılmaz; farklı görevler aynı adede sahip olabilir.
  Önce Task ID ile deduplicate edilir.

Çok renkli görev renk gruplarında kasıtlı olarak birden fazla göründüğü için bütün 3D
havuzu için tek bir "genel gerekli toplam" üretmek anlamsızdır ve yapılmaz.

---

# 14. PROGRESS EVENT MODELİ

Sayaç doğrudan üzerine yazılmaz; ilerleme **hareket/olay** olarak tutulur.

```text
ProgressEventKind:
  FAILURE_REPORTED    eksik/hatalı çıkan parçalar bildirildi
  SHORTAGE_RESOLVED   eksiğin bir kısmı yeniden basıldı
```

- Her olayın benzersiz UUID'si vardır; aynı olay iki kez uygulanamaz (PLAN `5.12`).
- `failureTotal` ve geçmiş hata toplamı olaylardan türetilir ve **silinmez** (PLAN `5.12`).
- Aşama sayaçları ve eksik miktarlar negatif olamaz.
- Olaylar append-only'dur; iptal/düzeltme için üçüncü bir tür **yoktur** — hata, ters
  hareketin kaydedilmesiyle düzeltilir.
- `progress_events` **yalnız bu iki türün** ve üretim açığı aritmetiğinin kaynağıdır.
  Yaşam döngüsü olayları buraya eklenmez ve `quantity > 0` zorunluluğu gevşetilmez.

---

# 15. HISTORY EVENT MODELİ  *(şema v7 ile eklendi)*

PLAN 12.15 geçmiş ekranından altı satır türü ister. Bunlardan yalnız ikisi
(`FAILURE_REPORTED`, `SHORTAGE_RESOLVED`) zaten kayıtlıydı. Geri kalanlar hiçbir iz
bırakmıyordu: her biri yalnızca bir alanın üzerine yazıyordu.

`history_events` tablosu bu boşluğu doldurur.

```text
history_events
  id                  benzersiz UUID (PK)
  kind                HistoryEventKind
  occurred_at         olay anı
  game_id             NOT NULL — olayın geçtiği/ait olduğu oyun
  task_id             yalnız oyun olaylarında NULL
  stage               yalnız aşama hareketinde
  previous_quantity   yalnız aşama hareketinde
  new_quantity        yalnız aşama hareketinde
```

İndeksler: `(occurred_at)`, `(game_id, occurred_at)`, `(task_id, occurred_at)`.

## Kurallar

- **Append-only.** Hiçbir DAO'da `UPDATE history_events` veya `DELETE FROM
  history_events` yoktur ve olmamalıdır. `HistoryDao` yalnız okur; yazma, olaya sebep
  olan transaction'ın içinden yapılır.
- **FK'lar RESTRICT, cascade yok.** Silme burada tombstone'dur; cascade, PLAN'ın
  korunmasını istediği anda geçmişi atardı.
- **Genel JSON payload, serbest metin veya geliştirici mesajı saklanmaz.**
- **`game_id` olay anında yazılır.** Bir görev oyununa hücre parçası üzerinden ulaşır;
  metne dönüştürme bu parçayı kaldırır, dolayısıyla oyun sonradan bulunamaz.
- **Aynı transaction'ın yazdığı bütün satırlar aynı `occurred_at` değerini taşır** —
  gerçekten aynı andırlar. Aralarında bir sıra yoktur; okumalar tekrarlanabilirlik
  için kimliğe göre bağ çözer ve ekran bu satırlara bir sıra okumamalıdır.
- **No-op olay yazmaz ve saat okumaz.** Kimlikler ilk DB yazısından önce hazırdır;
  kimlik üretimi ortada tükenirse hiçbir şey değişmez. Duplicate kimlik `ABORT` ile
  bütün transaction'ı geri alır.

## Olay yazan üretim yolları

```text
completeTask          → TASK_COMPLETED
reopenTask            → TASK_REOPENED
completePrimaryBatch  → TASK_COMPLETED (görevi bitiriyorsa)
resolveShortage       → TASK_COMPLETED (son eksiği kapatıyorsa)
reportFailure         → TASK_REOPENED  (tamamlanmış göreve bildirim)
setStageQuantities    → hareket eden her adım için TASK_STAGE_QUANTITY_CHANGED
                        + gerekiyorsa TASK_COMPLETED / TASK_REOPENED
completeGame          → geçiş yapan her görev için TASK_COMPLETED
convertTaskToText     → TASK_CONVERTED_TO_TEXT
TaskDao.softDelete    → TASK_DELETED
GameDao.softDelete    → GAME_DELETED
```

Bu satırların hepsi geçmiş ekranında görünür; ekranın nasıl okuduğu §32'dedir.

## İçe aktarma türleri  *(İş 2 dilim 2 ile eklendi)*

```text
IMPORT_CONFIRMED     onay sırasında, dokunulan her oyun için    (task_id NULL)
IMPORT_ROLLED_BACK   geri alma sırasında, her oyun için         (task_id NULL)
TASK_ROLLED_BACK     geri alınan her görev için
```

Enum değeri TEXT olarak saklandığı için bunlar **şema değişikliği gerektirmedi**;
`8.json` değişmedi ve sürüm 8 kaldı.

`HistoryEventEntity.init`'in yüklemi `isAboutGameItself` → **`namesNoTask`**
olarak yeniden adlandırıldı. Eski ad artık dar geliyordu: bir oyunun silinmesi
oyunun kendisiyle ilgilidir, bir içe aktarmanın onaylanması ise oyunun *içinde*
olan bir şeydir. Ortak yanları entity'nin bilmesi gereken tek şeydir — olayın
düştüğü tek bir görev yoktur.

`IMPORT_CONFIRMED` **görev sayısı taşımaz.** Üç adet sütunu
`TASK_STAGE_QUANTITY_CHANGED`'e aittir ve bir türde "aşama nereye geldi", başka
bir türde "kaç görev geldi" anlamına gelen bir sütun, `HistoryEventEntity`'nin
olmamak için tasarlandığı payload sütunudur. İçe aktarmanın kaç görev ürettiği
zaten `import_batches.created_task_count`'ta yazılıdır.

Engellenen bir geri alma **hiçbir olay yazmaz**: gerçekleşmemiş işlem geçmişe
girmez. Testle sabitlenmiştir.

## Yazıcısı olmayan türler

`TASK_RESTORED` ve `GAME_RESTORED` model içinde vardır ama **hiçbir üretim yolu
yazmaz**: uygulamada bugün geri yükleme akışı yoktur. Sırf olay üretmek için geri
yükleme özelliği eklenmemiştir. Aynı şekilde `TaskDao.softDelete`,
`GameDao.softDelete` ve `completePrimaryBatch` bugün **üretim kodundan
çağrılmamaktadır** (yalnız testler kullanır); yani uygulamada görev veya oyun silme
yolu henüz yoktur.

## Metne dönüştürmenin yeni davranışı

`Görevi metne dönüştür` artık görevi ve geçmişini **yok etmez**:

- Kelime hücrede aynı yerde düz metin olarak kalır, belge metni karakterine kadar aynıdır.
- Task **soft-delete** edilir; `TaskColor`, `TaskStage` ve bütün `ProgressEvent`
  kayıtları yerinde kalır.
- `TASK_CONVERTED_TO_TEXT` yazılır; ayrıca `TASK_DELETED` **yazılmaz** — dönüşüm ayrı
  bir olaydır, soft-delete yalnız görünürlük mekanizmasıdır.
- Görev havuzlardan, oyun tablosundan ve CSV export'tan çıkar.
- Mevcut kırmızı uyarı ve kullanıcı onayı korunur; uyarı metni artık geçmişin
  **silinmediğini** söyler.

---

# 16. PROGRESS / RENK ÇOĞALTMA KURALI

Çok renkli görev renk projection'larında görünse bile:

- ProgressEvent çoğaltılmaz.
- TaskStage çoğaltılmaz.
- Quantity çoğaltılmaz.
- Aynı Task ID korunur.
- Görev tamamlandığında tek görev değişir.

Test edilmesi gerekenler:

1. Üç renkli tek görev üç renk sorgusunda da aynı Task ID ile bulunur.
2. Aynı renk grubunda iki kez görünmez.
3. Quantity her renkte aynıdır.
4. ProgressEvent renk başına çoğaltılmaz.
5. TaskStage renk başına çoğaltılmaz.
6. Tamamlanma tek görevi etkiler.
7. Renk sırası değişince havuz üyeliği değişmez.
8. Tek öge çok renk ile çoklu görev modu karıştırılmaz.

---

# 17. UI GÜVENLİĞİ VE RENK DÜZENLEME UX

- Duplicate renk açıkça belirtilir; iki ilgili satır da işaretlenir.
- Satır ekleme/silme/sıralama sonrası odak mantıklı yerde kalır.
- Geçersiz satıra odak taşınır.
- **Escape yalnızca en içteki yüzeyi kapatır.**
- Dışarı tıklama kaydedilmemiş taslağı sessizce kaybetmez.
- Ctrl+Enter mevcut formu yalnızca bir kez kaydeder.
- DB hatasında panel açık kalır ve taslak korunur.
- DB Flow değişikliği açık paneli gereksiz yere kapatmaz.
- **Ham SQL, exception, stack trace, mutlak dosya yolu ve geliştirici mesajları
  kullanıcıya gösterilmez.**
- Silme, renk silme, görevi metne dönüştürme, oyun toplu tamamlama, temel renkleri
  geri yükleme ve import geri alma işlemlerinde onay istenir (PLAN `17.`).
- Renk hiçbir zaman tek bilgi taşıyıcısı değildir; her renk örneğinin yazılı adı vardır.
- Klavye ile bütün ana eylemlere ulaşılabilir; odak sırası ve görünür odak göstergesi
  vardır; metin ölçekleme ve yüksek DPI desteklenir.
- Uygulama metinleri `strings.xml` içinde tutulur, UI'ya dağınık hardcode edilmez.
  İlk dil Türkçedir.

---

## Faz 3 / İş 8 — klavye, odak ve ölçekleme taraması  *(TAMAMLANDI)*

Envanter gerçek `Screen.all` ve `AppScaffold` yönlendirmesinden çıkarıldı: 10
gezinme hedefi (Ana Sayfa, Oyunlar, 3D Baskı, Kartlar, Mukavva, Özel, İçe
Aktarma, Geçmiş, Renkler, Ayarlar) + açılış hata ekranı. Ortak ve tablo güdümlü
kanıt: `desktopTest/…/ui/navigation/AppKeyboardAndScalingTest` (6 test) —
`AppScaffold`, `Main`'in kurduğu **gerçek controller ve store'larla** geçici bir
Room DB üzerinde (uzun Türkçe adlı oyun, dört havuzda görev, renk, hata kaydı),
dört görünümde birleştirilir:

```text
1100×720 dp 1× (Main'in açılış boyutu) · 640×460 dp 2× (Main'in minimum penceresi)
1100×720 dp metin ×1,5 · 640×460 dp 2× metin ×1,3
```

Her ekran × görünüm için (40 kombinasyon): kenar çubuğu girişine yalnız Tab ile
ulaşılır, Enter veya Space ile açılır, ekranın başlığı yazılıdır; ekranda Tab ile
yürüyüş 150 durak içinde kenar çubuğuna **geri döner** (odak tuzağı yok); her
durak konuşan bir semantics düğümüdür ve kaydırma sonrası **pencerenin içinde**
çizilir; son 8 durak Shift+Tab ile birebir geri izlenir; hiçbir düğüm sağ kenarı
aşmaz. Ayrıca: 10 ekranda UUID, sınıf adı, SQL, yol ve domain enum adı taraması;
tablo hücresi tek duraktır ve Enter/F2 düzenleyiciyi açar; üç geri alınamaz onay
aşağıda.

```text
ekran / yüzey              klavye·Tab   Esc   yıkıcı odak   dar·2×   büyük metin  sızıntı  çift gönderim
─────────────────────────  ──────────  ────  ────────────  ───────  ───────────  ───────  ─────────────
kenar çubuğu + 10 ekran    App*        —     —             App*     App* (YENİ)  App*     —
Oyunlar: hücre, görev      TaskChipKeyboard, GameTableLayout, App* (hücre tek durak)          TaskChipKeyboard
Oyunlar: görev → metne     App* (YENİ)       ✓ Vazgeç (DÜZELTİLDİ)  App*
Oyunlar: toplu tamamlama   GameTableLayout (kapsayıcı odak; geri alınabilir işlem)
Oyunlar: arama/filtre      SearchToolbarRender, GameTableFilterState (Esc, odak dönüşü, dar, büyük metin)
Havuzlar: kart, aşama      PoolScreenLayout, PoolFilterState, App*
Havuz: görev → metne       App* (YENİ)       ✓ Vazgeç (DÜZELTİLDİ)  App*
Renkler: oluştur/düzenle   ColorManagementSurface, ColorPickerLayout (Esc, Ctrl+Enter, klavye tekeri)
Renkler: sil               App* (YENİ)       ✓ Vazgeç (DÜZELTİLDİ)  App*
Renkler: temel geri yükle  ColorManagementSurface (engelliyken odak çıkışta)
İçe Aktarma: inceleme      ImportReviewScreen (Tab/Shift+Tab, Esc, dar, çift gönderim, sızıntı)
İçe Aktarma: onay          ImportReviewScreen (meşgulken ikinci basış yok)
İçe Aktarma: geri alma     ImportRollbackScreen (odak Vazgeç, Esc, dar, çift gönderim)
İçe Aktarma: devam/kaldır  UnfinishedImportsSection (odak Vazgeç, Esc, 640×620 2×, çift gönderim)
İçe Aktarma: CSV hatası    CsvImportScreen (dar, klavye çıkışı, sızıntı)
Geçmiş                     HistoryScreenLayout (klavyeyle okuma) + App*
Ayarlar: yedek/üzerine yaz SettingsScreen (Esc, odak dönüşü, basılı Enter tek yedek, dar)
Ayarlar: geri yükle        RestoreSection (odak Vazgeç, Esc, dar + büyük ölçek, çift gönderim)
Ayarlar: saklama sayısı    RetentionSection (dar 2×, sızıntı)
Dışa aktarma (Oyunlar)     ExportScreen (Esc, dar, uzun ad, 7 hata cümlesi sızıntısız)
Açılış hata ekranı         StartupErrorScreen (2×, sızıntı, çıkış erişilebilir)
```
`App*` = `AppKeyboardAndScalingTest`.

**Bulunan ve düzeltilen kusurlar** — `fix(ui): keep every screen usable with keyboard and scaling`:

1. **Oyun tablosu hücresi iki Tab durağıydı.** `.focusable()` ile
   `combinedClickable` yan yana iki odak hedefi kuruyordu; ikinci durak ekran
   okuyucuya sessizdi ve klavye hücre ile ilk görevi arasında "kayboluyordu"
   (tarama: "stop holds nothing"). Hücre artık tek `focusable`; tek tıklama odağı
   alır, çift tıklama `detectTapGestures` ile düzenleyiciyi açar (görev tikinin
   kalıbı), Enter/F2 değişmedi, semantics tıklaması düzenleyiciyi açar (önce
   etiketli ama boş bir eylemdi). `GameTableLayoutTest`'in bu şekli sabitleyen iki
   kaynak iddiası yeni şekle çevrildi (çift tıklama + tek odak hedefi).
2. **En küçük pencerede ve büyük metinde oyun tablosu görünmüyordu.** Tablonun
   üstündeki kontroller kaydırılamaz sabit bir sütundu; 640×460'ta tablo 0
   yükseklikte, büyük metinde dışa aktarma düğmesi pencerenin dışında kalıyordu.
   Kontroller artık `pencere − 200 dp` (en az %40) yüksekliğe kadar yer alır ve
   ötesinde kendi içinde kayar. Açılış boyutunda görünüm değişmez.
3. **Geri alınamaz üç onayda başlangıç odağı yıkıcı düğmedeydi / hiçbir
   düğmede değildi.** Renk silme (`Rengi sil`) ve tablodaki `Metne dönüştür`
   odağı yıkıcı düğmeye veriyordu; havuzdaki `Metne dönüştür` kapsayıcıda
   bırakıyordu. Üçü de artık `Vazgeç`'te başlar; Enter rengi/görevi korur
   (Vazgeç yalnız en içteki yüzeyi kapatır: dönüştürmede menüye döner).
   Ctrl+Enter kısayolu bilinçli olarak korunur. Mutasyonla doğrulandı: eski odak
   geri konunca üç test de düşüyor.

**Kusur sayılmayan, kayda geçen gözlemler:**

- Tembel (lazy) listelerde (Renkler, tablo, havuzlar, geçmiş) dışarıdan Tab ile
  girildiğinde odak görünen ilk öğeye, Shift+Tab ile görünen son öğeye iner;
  liste içinde her iki yönde de **bütün** öğelere ulaşılır ve odaklanan öğe
  görünür alana kaydırılır. Compose'un standart davranışıdır; lazy listeler
  yeniden tasarlanmadı.
- Oyun toplu tamamlama onayı odağı kapsayıcıya verir (Enter bir şey yapmaz,
  Escape kapatır); işlem geri alınabilir olduğu için değiştirilmedi.
- Görsel odak göstergesi piksel olarak test edilmez (piksel testi eklenmedi);
  kaynağı `focusOutline` kalıbıdır.

Programatik masaüstü smoke: `XDG_DATA_HOME`/`XDG_CONFIG_HOME` geçici bir dizine
yönlendirilerek `./gradlew --no-daemon run`; pencere açıldı, DB ve kilit geçici
dizinde oluştu, `wmctrl -i -c` ile kapatıldı, çıkış 0, `-wal`/`-shm` kalmadı,
log'da exception yok; dizin silindi. *(Bugün pencere yalnız `SafeWindowCloser` /
`./gradlew desktopWindowSmoke` ile kapatılır; §30.)* Gerçek pencerede el ile klavye turu
yapılmadı (kullanıcı başında değildi; ekran görüntüsü alınmadı).

# 18. KİMLİK VE OFFLINE-FIRST

- Bütün kalıcı varlıkların kimliği **uygulama tarafında üretilen UUID v4**'tür.
- Veritabanında auto-increment kimlikler domain kimliği olarak kullanılmaz.
- Silinen oyun ve görevler hemen fiziksel olarak silinmez; `deletedAt` tombstone
  kullanılır. Kalıcı fiziksel temizleme yalnız açık bir bakım işlemi olarak ve yedek
  alındıktan sonra yapılabilir (PLAN `5.2`).
- Renk bu kuralın açık istisnasıdır (§11).

Amaç: ileride sync gelirse silinen kayıtların geri dönmemesi, iki cihazın aynı kaydı
üzerine yazmaması ve sunucu olmadan kayıt oluşturulabilmesi.

---

# 19. İÇE AKTARMA MİMARİSİ

İki kaynak biçimi desteklenir ve **ikisi de aynı boru hattını kullanır**:

```text
.xlsx / .csv seç
   ↓
salt okunur oku, SHA-256 fingerprint
   ↓
snapshot (CSV için referans yerleşimli sentetik sayfa)
   ↓
RawImportBlock
   ↓
DraftTask / DraftTaskColor   (inceleme ekranı)
   ↓
kullanıcı onayı
   ↓
otomatik JSON snapshot (yazılır + GERÇEK okuyucuyla doğrulanır)   ← Faz 3 / İş 4 dilim 3
   ↓
tek transaction: 15 tablo yeniden okunur, snapshot'la karşılaştırılır
   ↓
ImportBatchCell (hücrelerin ÖNCEKİ metni)  →  CellSegment / Task / TaskColor / TaskStage
```

**İkinci bir import mimarisi yazılmaz.** CSV, mevcut boru hattına referans yerleşimli
bir `SheetSnapshot` üreterek bağlanır.

- Kaynak dosya hiçbir zaman yerinde değiştirilmez.
- Import aşamasında veri doğrudan domain'e uygulanmaz; önce ham veri ve DRAFT katmanı.
- Aynı dosyanın fingerprint'i daha önce varsa duplicate akışı açıkça ele alınır.
- Batch + ham bloklar tek transaction sınırında yazılır; hata durumunda rollback.
- Tek bir hücredeki hata bütün dosya aktarımını kaybettirmemelidir.
- Onaylanmamış taslak, uygulama kapanıp açılınca yeniden açılabilmelidir.

## Onayın sakladığı hücre anlık görüntüsü  *(v8, Faz 3 / İş 2 dilim 1)*

`ImportDao.confirmDraftBatch` artık, ilk domain yazımından **önce**, batch'in
görev ekleyeceği her hücre için bir `ImportBatchCell` satırı yazar:

```text
plannedConfirmationOf → documentsBefore     zaten okunuyordu (tek toplu SELECT)
        ↓
documentTextsBefore   aynı satırların uç uca okunuşu, cell_id → metin
        ↓ (üç yerde kullanılır, tek hesap)
  1  ayırıcı gerekip gerekmediğine karar verir  (taskNeedsSeparatorAfter)
  2  ImportBatchCell.document_before olarak saklanır
  3  postcondition'da kullanıcı metninin korunduğunu kanıtlar
```

Kurallar:

- Hücre başına **tam bir** satır; aynı hücreyi hedefleyen kaç taslak olursa olsun,
  hepsi kendilerinden önceki tek belgeyi paylaşır (`distinct()`).
- Boş hücre `""` olarak saklanır. Satırın **hiç olmaması** başka bir şey demektir:
  o batch o hücreye hiç yazmadı, ya da batch v8'den önce onaylandı.
- Metin harfi harfine saklanır: Türkçe harfler, satır sonu, çift boşluk ve iki
  uçtaki boşluk dâhil; trim veya normalize edilmez.
- Aynı transaction'ın içindedir; onay yarıda kalırsa anlık görüntü de kalmaz.
- Postcondition satırları geri okur ve beklenenle karşılaştırır — **tek** ek
  SELECT, batch büyüklüğünden bağımsız.
- `document_after` saklanmaz: beklenen metin `document_before` + görev adları +
  `taskNeedsSeparatorAfter` ile yeniden hesaplanır. İki hesap ayrışamaz.
- Anlık görüntü, geri almanın dayandığı kanıttır.

## Geri alma motoru  *(v8, Faz 3 / İş 2 dilim 2)*

Kural metni PLAN `11.4.4`'tedir. Repo tarafındaki karşılıkları:

```text
ImportDao.previewRollback(batchId)        salt okunur; tavsiye niteliğinde
ImportDao.rollBackConfirmedBatch(...)     tek transaction; iki sonuç, üçüncüsü yok
ImportRollbackStore                       SQLiteException → COULD_NOT_SAVE
planImportRollback(facts)                 SAF karar; preview ve transaction ortak
```

**Kararı tek bir saf fonksiyon verir.** `ImportRollbackFacts` toplu okumalarla
doldurulur, `planImportRollback` hiçbir saat, veritabanı veya kimlik üretimi
olmadan karar verir. Önizleme ve yazan transaction aynı fonksiyonu çağırır: iki
ayrı "bu güvenli mi" uygulaması, birbirine düşmeyi bekleyen iki cevap olurdu ve
denenmemiş olan, işe yarayan olurdu. Önizleme yine de **bağlayıcı değildir**;
transaction bütün satırları kendi içinde yeniden okur.

**Engelleyen bir plan boştur.** `failure != null` olduğunda `taskIds`, `cells` ve
`gameIds` boş döner — "yine de yapılabilecek kısım" diye bir şey olmadığını tip
söyler.

Hücrenin nasıl geri yüklendiği:

```text
sınır  = metni tam olarak document_before'u yazan parça öneki
önek   = kullanıcının kendi parçaları — DOKUNULMAZ, kimlikleri korunur
sonek  = bu batch'in TASK parçaları + yazdığı tek boşluklar — yalnız bunlar silinir
```

Sınır tektir, çünkü hiçbir parça boş metin katkısı yapmaz (görevin adı vardır,
boşalan metin satır bırakmaz — PLAN `16.`). Sonek silindiğinde önek zaten
`0..N-1` sırasındadır; yeniden numaralama gerekmez ve yan yana düz metin
oluşmaz.

İki ayrı denetim birlikte aranır: **sözcükler** (`document_before` + görev
adları, onayın kullandığı `taskNeedsSeparatorAfter` ile) ve **parçalar** (sonekte
yalnız bu batch'in görevleri ve tek boşluklar; önekte bu batch'ten görev yok;
numaralama sağlam). İlki kullanıcının yazdığı bir harfi yakalar, ikincisi aynı
hücreye yazan ikinci bir import'u.

Transaction sırası: toplu okuma → sınıflandırma → engel varsa **yazmadan** dön →
`clock.now()` bir kez + bütün olay kimlikleri → tombstone'lar →
`TASK_ROLLED_BACK` → sonek parçalarının silinmesi + hücre `updated_at` →
`IMPORT_ROLLED_BACK` → koşullu `CONFIRMED → ROLLED_BACK` → postcondition.

Postcondition dört tablo sayımını önce ve sonra karşılaştırır: hiçbir görev
satırı yok olmadı, hiçbir progress olayı silinmedi, hiçbir eski geçmiş satırı
gitmedi, ve `cell_segments`'ten yalnız planlanan sonek kadar satır eksildi.
Yazılan geçmiş satırları **kimlikle** doğrulanır; tür ve zamanla saymak, aynı
milisaniyede onaylanmış iki import'un birbirinin satırlarını sayması demekti.

## Geri alma arayüzü  *(Faz 3 / İş 2 dilim 3)*

İçe Aktarma ekranı artık iki adlandırılmış liste taşır:

```text
Devam eden içe aktarmalar     DRAFT   — mevcut inceleme akışı, değişmedi
Onaylanmış içe aktarmalar     CONFIRMED + ROLLED_BACK
```

```text
ImportDao.observeSettledBatches()   tek tablo, JOIN yok, imported_at DESC, id
ImportRollbackStore.observeSettledImports()  → SettledImport (dosya, sayfa,
                                                görev sayısı, durum)
ImportRollbackController            tek açık yüzey, çift gönderim koruması
SettledImportsSection               liste + üç diyalog
```

Kurallar:

- **Liste JOIN'siz okunur.** Göreve veya hücreye açılan bir JOIN, 42 görev
  üreten bir batch'i listede 42 kez gösterirdi ve her satıra bir `Geri al`
  düğmesi koyardı. `import_batches` satırı zaten `created_task_count` taşır.
  Ölçülmüş invariant §29'dadır.
- **Arayüz hiçbir kuralı yeniden hesaplamaz.** `canBeTakenBack` yalnız
  `status == CONFIRMED` demektir ve karar değildir; kararı motor iki kez verir.
- **Önizleme bağlayıcı değildir.** `Geri al`'a basmak `rollBack()` çağırır,
  motor bütün satırları transaction içinde yeniden okur; arada değişen bir hücre
  `Refused` durumuna düşer ve panel açık kalır.
- **Tek yüzey.** `ask()` yalnız `Closed` durumundan, `takeBack()` yalnız
  `Offered` durumundan çalışır; ikinci tıklama ve tekrarlanan Enter ikinci bir
  okuma veya ikinci bir transaction başlatamaz. Düğme de yüzey açıkken
  `enabled = false`'tur.
- **Akış açık pencereyi kapatmaz.** DB'den gelen yeni liste yalnız satırları
  tazeler; açık batch gerçekten `ROLLED_BACK` olduysa ya da kaybolduysa yüzey
  kapanmaz, **engelleme metnine döner**. Başarı penceresi listenin arkadan
  gelmesiyle bozulmaz.
- **Escape en içteki yüzeyi kapatır** ve bunu diyalogun kendi
  `onPreviewKeyEvent`'i yapar: tuş, odağı tutan öğeye gider, o da diyalog
  katmanındadır — arkadaki bölüme konan bir işleyici hiç sorulmaz. Yükleme ve
  yazma sırasında Escape ve dışarı tıklama etkisizdir.
- **Odak geri döner.** `lastAsked` jetonu, yüzey kapandığında klavyeyi işlemi
  başlatan satırın düğmesine geri koyar ve jeton harcanır.
- Onay penceresi PLAN 11.4.4'ün istediği dördünü söyler: kaç görev gideceği,
  işlemin bütün batch'i kapsadığı, tamamlanma işaretlerinin geri alınmayacağı ve
  işlemin geri alınamaz olduğu.
- Engelleme penceresinde **onay düğmesi yoktur**; yalnız kapatılabilir. Kısmi
  geri alma teklif edilmez.

## Batch durumunun Türkçesi

```text
DRAFT       → Taslak
CONFIRMED   → Onaylandı
ROLLED_BACK → Geri alındı
```

Tek yer: `ui/PoolNames.kt` içindeki `importStatusNameOf`. Duplicate parmak izi
uyarısı eskiden `earlier.status.name` yazıyordu, yani Türkçe bir cümlenin
ortasında `CONFIRMED` görünüyordu; o kusur bu dilimde kapandı ve ekran testi
ham enum, UUID, SQL ve exception metni için tarama yapıyor.

## Onay penceresinin artık doğru olan cümlesi

Manuel turun bulduğu ikinci kusur: içe aktarma onay penceresi
`Bu işlem tek seferliktir ve bu sürümde geri alınamaz.` diyordu. Bu cümle,
geri alma arayüze bağlandığı anda **yanlış** oldu; tutulmayan bir söz, hiç
verilmemiş sözden kötüdür, çünkü onu okuyan kullanıcı artık var olan çıkışı
aramaz. Yeni metin geri almanın koşulunu söyler ve nerede olduğunu gösterir.
`ImportReviewScreenTest` bunu sabitler: pencere `geri alınamaz` diyemez,
`geri alınabilir` demelidir.

## CSV içe aktarma sözleşmesi

```text
zorunlu başlık : game, source_type, raw_text
sütun sırası   : serbest
fazla sütun    : yoksayılır
eksik/duplicate zorunlu sütun : reddedilir
ayırıcı        : virgül veya noktalı virgül; başlık kaydı RFC ayrıştırılarak
                 belirlenir (karakter sayarak değil)
kodlama        : katı UTF-8, replacement karakteri kabul edilmez
BOM            : varsa tek bir tanesi atılır
veri hücreleri : trim edilmez
boş fiziksel kayıt : atlanır
tırtıklı satır : satır numarasıyla reddedilir
sıra           : dosya sırası = RawImportBlock sırası
aynı satırlar  : asla deduplicate edilmez
= + - @ ile başlayan hücreler : düz metin olarak korunur
```

---

# 20. XLSX OKUYUCU

Reader:

- workbook'u salt okunur açar, dosyayı değiştirmez, kaynağı doğru kapatır,
- hücre türlerini güvenli biçimde snapshot'a çevirir,
- tema/indexed/RGB/tint renklerini çözer,
- rich-text koşullarını korur.

Referans dosyadan bilinen önemli durumlar:

- Tamamlanmış oyunların yeşil işaretleri doğrudan RGB olmak zorunda değildir; tema
  rengi kullanılabilir.
- 3D renk vurgularının önemli bir kısmı rich-text koşulları içinde bulunabilir.

Bu nedenle yalnızca basit hücre dolgusu okumak yeterli değildir.

---

# 21. XLSX HATA SINIRLARI

Geniş `catch(RuntimeException)` yaklaşımı **kusurdur ve düzeltilmiştir**. Beklenmeyen
`NullPointerException`, `IllegalStateException`, `IllegalArgumentException` ve diğer
programlama/invariant hataları bozuk Excel gibi sınıflandırılmaz.

```text
EncryptedDocumentException        → ENCRYPTED
eski .xls                         → LEGACY_XLS_FILE
geçersiz OOXML                    → NOT_AN_XLSX_FILE
bilinen bozuk paket               → DAMAGED_FILE
bilinen POI güvenlik limiti       → REJECTED_BY_SAFETY_LIMIT
beklenmeyen IllegalStateException → aynı exception olarak yeniden yükselir
beklenmeyen NullPointerException  → XlsxReadException içine maskelenmez
fatal Error                       → yakalanmaz
```

Cause zinciri döngüye karşı korumalıdır; bilinen POI cause'u sınıflandırılabilir,
bilinmeyen cause zinciri maskelenmez.

Testlerde geniş `Exception`/`Throwable` beklentisi kullanılmaz.

---

# 22. XLSX SNAPSHOT VE HAM METİN

Snapshot immutable'dır ve şunları korur: sayfa bilgisi, hücre konumu, ham/gösterim
metni, hücre tipi, renk bilgisi, rich-text bilgisi, indeks/aralık bilgileri.

Orijinal metin indeksleri korunur. Rich-text, `**` işaretleri, tamamlanma işaretleri
ve diğer ipuçları **orijinal ham metne göre** indeks taşır. Unicode dönüşümlerinin
indeksleri kaydırmasına izin verilmez.

---

# 23. İÇE AKTARMA İPUÇLARI

İpucu kaynakları: yeşil hücre, sütun türü, `**`, belirsiz renk ifadeleri,
`MISSING` / `BORROWED` durumları.

## Kritik kural

İpuçları **yalnız öneri üretir**. Hiçbir ipucu Game'e, Task'a veya tamamlanma
durumuna **otomatik uygulanmaz**; `HintDecision.PENDING` olarak kalır ve ancak
kullanıcı onayıyla işlenir.

- Tamamlanma işareti nihai domain durumu değildir.
- Yeşil hücre tespiti aşırı geniş tolerans kullanmaz; amaç false positive üretmemektir.
- Belirsiz renk algılama yalnız açık bağlam/bağlaçlarla çalışır ve negatif örneklerle
  test edilir.
- Uygulama otomatik ve geri döndürülemez biçimde toplu görev üretmez; kullanıcı onayı
  gerekir.

---

# 24. IMPORT BLOCK KURALLARI

- Başlıklar `RawImportBlock` olarak yazılmaz.
- Her `SourceColumnType` için uygun ham bloklar üretilir.
- Ham metin kayıpsız korunur; yeşil, `**`, rich-text ve orijinal metin indeksleri
  kaybolmaz.
- `createdGameCount`, `createdTaskCount`, `rawBlockCount` raporlanır ve dilimin
  kapsamıyla uyumlu olmalıdır.

---

# 25. CSV EXPORT SÖZLEŞMESİ

Başlık, tam olarak ve bu sırada:

```text
game,column,task,pool,colors,required_quantity,status,notes
```

- Her gerçek `Task` için **tam bir satır**; çok renkli görev renk başına çoğaltılmaz.
- Aktif + tamamlanmış + bilgi eksik görevler yazılır.
- Soft-delete edilmiş görev ve silinmiş oyunun görevleri yazılmaz.
- **Ekrandaki arama ve filtreler dosyanın kapsamını değiştirmez.**
- ProgressEvent / TaskStage / import batch / segment satırlaştırılmaz.
- Ek sütun ve yan dosya yoktur.

## Alanlar

```text
game               güncel oyun adı, kullanıcı metni aynen
column             segmentin bulunduğu GameCell'in gerçek columnType'ı
task               gerçek görev adı; belge metninden türetilmez
pool               görevin gerçek poolType'ı
                   → column ve pool ayrı okunur; biri diğerinden türetilmez,
                     uyuşmazlık bütünlük hatasıdır
colors             slotIndex artan sırayla, "|" ile birleşik
required_quantity  yalnız rakam; "×10" gibi UI gösterimi yok; çok renkli görev için
                   tek değer; renk/aşama başına yeniden hesaplanmaz
status             açık | tamamlandı | bilgi eksik
notes              güncel not aynen; null/boş → boş hücre; trim yok
```

## Renk yazımı

```text
tek renk    Gri
çok renk    Gri|Siyah
sıfır renk  boş hücre        ("Renk seçilecek" gibi UI metni export edilmez)
```

Kaçış: önce `\` → `\\`, sonra `|` → `\|`, sonra `|` ile birleştir.

Duplicate renk ilişkisi **export invariant hatasıdır**, sessizce merge edilmez.
Geçersiz/boşluklu/duplicate `slotIndex` durumunda export başarısız olur ve **hedef
dosya değiştirilmez**.

## Status önceliği

```text
needsInfo > completed > open
```

`needsClassification` tek başına `bilgi eksik` anlamına gelmez.
`MISSING`, `BORROWED`, aşama durumu ve mevcut eksik baskı miktarı yeni status oluşturmaz.

## Dosya biçimi

UTF-8 **BOM'lu**, virgül ayırıcılı, **her kayıt CRLF ile biter** (sonuncusu dahil),
RFC 4180 kaçışı. Aynı anlık görüntü her zaman **bayt bayt aynı** dosyayı üretir.

## Formül enjeksiyonu koruması

Hücrenin ilk karakteri tab/CR/LF ise, ya da ilk **whitespace olmayan** karakteri
`= + - @` ise hücrenin gerçek başına `'` eklenir. Baştaki boşluklar korunur.
`required_quantity` bu korumadan geçmez. Koruma yalnız dosyayı etkiler; veritabanı
değişmez.

## Dosya güvenliği

Sıra: hedef seçimi → overwrite kararı → tek read snapshot → bayt üretimi → atomik
yazma. Geçici dosya hedefle **aynı dizinde** açılır, sonra atomik taşınır. Atomik
taşıma desteklenmiyorsa **veri kaybettiren fallback yoktur**; tipli hata verilir ve
eski dosya korunur. Her hatada geçici dosya silinir ve hedef bayt bayt aynı kalır.

Depolama okuması düşerse (`SQLiteException`) export tipli `COULD_NOT_READ` ile
durur; hiçbir dosya oluşturulmaz veya değiştirilmez ve ekran yeniden denemeye
izin verir (İş 5'te eklendi, aşağıda).

## Faz 3 / İş 5 — doğrulama matrisi  *(TAMAMLANDI)*

PLAN `18.` Faz 3 İş 5 ("CSV görev dışa aktarmayı doğrula") ve Faz 3 testi
"CSV dışa aktarma doğruluğu" yalnız başlıktır; ölçüt yukarıdaki sözleşmedir
(PLAN `11.8` başlığı + `14.4` + bu bölüm). Her madde, gerçek üretim yolunu
kullanan en az bir testle eşleştirildi. `Contract` = yeni
`platform/exportfiles/TaskExportContractTest` (gerçek Room DB + `TaskExportStore`
+ `ExportController` + `DesktopExportFileGateway` + gerçek `AtomicFileWriter`).

```text
madde                                  kanıt (test sınıfı)
başlık ve sütun sırası                 TaskCsvTest · Contract "exactly the bytes"
her Task tek satır, çok renk çoğalmaz  TaskExportSnapshotTest · Contract "progress, stages and flags"
aktif + tamamlanmış + bilgi eksik      TaskExportStoreTest · Contract "a thousand tasks"
soft-delete görev / silinmiş oyun      TaskExportStoreTest, TaskExportEndToEndTest (görev),
                                       TaskExportSnapshotTest (saf) · Contract "deleted game" (GERÇEK DB)
metne dönüştürülmüş görev yazılmaz     TaskConversionHistoryTest
arama/filtre kapsamı değiştirmez       yapısal: TaskExportSource.exportedTasks() parametre almaz;
                                       ExportScreenTest kapsam cümlesi; TaskExportStoreTest filtre 0 ifade
progress/stage/batch/segment satır olmaz Contract "progress, stages and flags" (gerçek olay + aşama)
alan kaynakları (güncel ad, gerçek     TaskExportStoreTest · Contract "stored now" (oyun adı değişti,
  columnType, gerçek poolType, notlar)  renk renameAndRecolor ile, görev editTask ile)
column ≠ pool → bütünlük hatası        TaskExportSnapshotTest · Contract "pool its column does not feed"
renkler slotIndex sırası, "|" birleşik TaskExportStoreTest · Contract "exactly the bytes", "progress…"
kaçış \ → \\, | → \|                   TaskCsvTest (geri okuma) · Contract "exactly the bytes" (gerçek renk adı)
sıfır renk → boş hücre                 TaskCsvTest · Contract "exactly the bytes"
duplicate renk / bozuk slot → hata,    TaskExportSnapshotTest · Contract "broken colour slot" (eski hedef
  hedef değişmez                        dosya bayt bayt aynı, klasörde başka dosya yok)
required_quantity yalnız rakam / boş   TaskCsvTest · Contract "exactly the bytes"
status önceliği needsInfo>completed>open TaskExportSnapshotTest · Contract (needsInfo + completed → bilgi eksik)
MISSING/BORROWED/eksik miktar/aşama     Contract "progress, stages and flags" (hepsi "açık")
  status üretmez
notes aynen, null/boş → boş, trim yok  TaskCsvTest · Contract "exactly the bytes"
UTF-8 BOM (EF BB BF, bir kez) + CRLF   CsvWriterTest · Contract "exactly the bytes", "a thousand tasks"
  (son kayıt dâhil)
RFC 4180: virgül, tırnak, satır sonu   CsvWriterTest · TaskExportEndToEndTest · Contract "exactly the bytes"
Türkçe / Unicode / emoji               CsvWriterTest · TaskExportEndToEndTest · Contract "a thousand tasks"
formül koruması (= + - @, tab/CR/LF,   SpreadsheetSafeTextTest · TaskCsvTest · TaskExportEndToEndTest ·
  baştaki boşluk korunur, adet hariç)   Contract "exactly the bytes" (' -iki, '\tsekmeli)
1.000+ görev                           TaskExportStoreTest (1.082, sorgu) · Contract (1.200 üretilen,
                                       1.054 yazılan: silinmiş görev + silinmiş oyun hariç)
deterministik bayt                     TaskCsvTest · TaskExportEndToEndTest (8) · Contract (1.054 görev, iki export)
sorgu şekli + 0 yazma + tek transaction TaskExportStoreTest
atomik yazma, eski hedef korunur       AtomicFileWriterTest · Contract: NOT_ATOMIC, WRITE_FAILED (yarım
                                       yazılmış geçici dosya), NOT_WRITABLE — üçünde de eski hedef bayt
                                       bayt aynı ve klasörde yalnız o dosya
üzerine yazma onayı / vazgeç           ExportControllerTest · TaskExportEndToEndTest · ExportScreenTest
depolama okuması düşerse               Contract "storage that will not answer" → COULD_NOT_READ (YENİ)
kullanıcıya yol/teknik hata sızmaz     ExportScreenTest "every failure…" — 7 ExportFailure'ın hepsi ekranda,
                                       720 px, yasaklı: Exception, SQL, SELECT, /, \, java., dev.pnptracker,
                                       null, tasks, enum adları; 7 ayrı cümle · TaskExportEndToEndTest
                                       (state'te yol yok) · TaskExportSnapshotTest (ret mesajı)
```

**Bulunan ve düzeltilen üretim kusuru** — `fix(export): report storage that will
not answer instead of hanging`: `TaskExportStore` bir `SQLiteException`'ı olduğu
gibi bırakıyordu; `ExportController` yalnız `TaskExportException` yakaladığı için
exception coroutine'den dışarı çıkıyor ve ekran `Writing` (meşgul, düğme kapalı)
durumunda kalıyordu. Test önce **kırmızı** koştu (ham `SQLiteException` ile düştü),
sonra düzeltildi. Düzeltme `BackupController`'ın `COULD_NOT_READ_DATABASE`
kalıbının aynısıdır: yeni `ExportFailure.COULD_NOT_READ` + tek Türkçe cümle
(`export_error_could_not_read`); `IllegalStateException` ve diğer programlama
hataları **yakalanmaz**. Başka davranış değişmedi.

---

# 25.1 JSON YEDEK VE GERİ YÜKLEME SÖZLEŞMESİ  *(Faz 3 / İş 3 — TAMAMLANDI)*

Bağlayıcı metin PLAN `14.4` (alt bölümleri `14.4.1`–`14.4.6`), `12.16` ve `16.`
bölümlerindedir. Çelişkide PLAN kazanır.

**Dört dilimin dördü de uygulanmıştır**: belge, kapsam, sıralama, tek transaction
okuma, kanonik yazıcı ve checksum (Dilim 1); `Ayarlar` ekranı ve atomik dosya
yazma (Dilim 2); güvenilmeyen dosyayı okuma, doğrulama ve geçici bir Room v8
veritabanında deneme (Dilim 3); restore öncesi güvenlik yedeği, canlı
veritabanındaki tek replace transaction'ı ve arayüz (Dilim 4). **İş 3 bitmiştir.**

## Biçim

```text
dosya            tek düz UTF-8 JSON, uzantı .json, sıkıştırma YOK
kütüphane        kotlinx-serialization-json + Kotlin serialization derleyici eklentisi
                 (Dilim 1'de eklendi: 1.11.0, yalnız commonMain; §4)
boyut sınırı     64 MiB, parse BAŞLAMADAN uygulanır
kanoniklik       alan sırası sabit, satırlar sabit anahtarla sıralı, kayan nokta yok
determinizm      aynı DB iki kez → createdAt dışında bayt bayt aynı dosya
```

Zarf:

```text
format               "pnp-tracker-backup"
formatVersion        1        (Room şema sürümünden BAĞIMSIZ)
appVersion           bilgi alanı; tek başına restore'u engellemez
sourceSchemaVersion  desteklenenden yeniyse reddedilir
createdAt            insan okusun diye metin
dataSha256           yalnız data'nın kanonik UTF-8 baytlarının SHA-256'sı
data                 tablo başına bir dizi
```

- Zarf **kendi kendini hash'lemez**; hash yalnız `data` üzerindedir.
- Checksum uyuşmazlığı, **kalıcı hiçbir yazma başlamadan** durdurur.
- Daha yeni `formatVersion` reddedilir; kullanıcıdan uygulamayı güncellemesi istenir.
- İlk sürümde **eski format yükseltme zinciri yoktur**; `1` tek bilinen sürümdür.
- Satır zamanları DB'deki epoch millis değerini **aynen** taşır; yalnız `createdAt`
  metin biçimindedir.
- Kimlikler ve enum değerleri kayıpsız taşınır.
- Null alan **atlanmaz**, açıkça `null` yazılır.
- Bilinmeyen alan / bilinmeyen tablo / eksik zorunlu alan / tekrarlanan JSON
  anahtarı → **reddedilir.** Sessizce yok sayma yoktur.

## Kapsam

Şema v8'deki **15 tablonun tamamı**. Tablo listesi, sıralama anahtarları ve geri
yükleme sırası PLAN `14.4.2`'dedir; buraya kopyalanmaz ki iki yerde sapmasın.

Kısaca: temel ve özel renkler, renk alias'ları, oyunlar, hücreler ve bütün
parçalar, görevler + renk yuvaları + aşamalar, progress olayları, history
olayları, import batch'leri, ham bloklar ve ipucu kararları, taslak görevler ve
taslak renkleri, hücre anlık görüntüleri, tombstone kayıtları, onaylanmış ve geri
alınmış içe aktarmalar.

Projection'lar ve ekranda yeniden türetilebilen özetler ayrı kayıt olarak
**yazılmaz**. Sütun olarak saklanan sayaçlar türetilmiş değil saklanan durumdur ve
taşınır.

Kimlikler ve zaman damgaları **yeniden üretilmez.** Sebebi tercih değil
zorunluluktur: `11.4.4` bir görevin dokunulmamış sayılmasını
`updatedAt == createdAt` eşitliğine bağlar. Zaman damgasını yeniden yazan bir geri
yükleme, geri yüklediği bütün `CONFIRMED` batch'leri geri alınamaz yapardı.

## Seçilen restore mimarisi: A′

```text
dosyayı güvenmeden oku
→ zarf / sürüm / checksum doğrulaması
→ yapısal ve domain doğrulaması
→ geçici Room v8 DB'ye yükleme
→ foreign_key_check + invariant denetimleri
→ kullanıcı onayı            ← buraya kadar gerçek DB'ye TEK BAYT yazılmadı
→ restore öncesi güvenlik yedeği
→ canlı DB'de TEK replace transaction
→ yeniden okuma / hash doğrulaması
→ Flow ve arayüz yenilenmesi
```

Canlı DB üzerinde: tablolar **ters** yabancı anahtar sırasıyla temizlenir, satırlar
doğru sırayla yazılır, hepsi **tek transaction**'dır, gereken yerde
`defer_foreign_keys` kullanılır ve commit'ten önce **açıkça** `foreign_key_check`
çalıştırılır. Herhangi bir hata bütün transaction'ı geri alır; **yarım restore
oluşamaz.**

- DB dosyası, `-wal` ve `-shm` **dosya sistemi düzeyinde değiştirilmez**; bağlantı
  kapatılıp dosya takas edilmez.
- Restore **merge değildir**; yedek mevcut verinin tamamının yerine geçer.
- Restore sonrası **yeniden başlatma gerekmez**; Room invalidation/Flow ekranları
  tazeler, açık ve bayat düzenleme yüzeyleri güvenli biçimde kapatılır veya yenilenir.
- Restore **history event yazmaz**; yüklenen geçmiş yedeğin geçmişidir.
- Aynı yedeği ikinci kez yüklemek aynı sonucu verir (idempotent).

## Restore öncesi güvenlik yedeği

Son onaydan sonra, canlı DB'ye dokunulmadan önce
`$XDG_DATA_HOME/pnp-tracker/backups/` altına, **manuel yedekle aynı kanonik
biçimde**, tarihli ve çakışmaya dayanıklı adlı bir dosya atomik olarak yazılır.

- **Yazılamazsa restore hiç başlamaz.**
- Tamamlanmadan canlı DB'ye yazılmaz.
- Onay ekranı bunun kullanıcının geri dönüş yolu olduğunu söyler.
- Döngüsel saklama, sayı ayarı ve eski yedek temizliği **İş 4'e aittir**; burada yok.
- Mutlak yol, kişisel veri ve dosya içeriği hata/log mesajına sızmaz.

## Kullanıcı akışı

`Ayarlar` ekranı bu işte gerçek bir gezinme hedefi olarak açılır (PLAN `12.1`,
`12.16`). İçinde `Yedek oluştur` ve `Yedekten geri yükle` bulunur. Dosya
doğrulanmadan yıkıcı onay gösterilmez; çift gönderim engellenir; hata metinleri
Türkçe ve eyleme dönüktür; ham enum, UUID, SQL, mutlak yol, exception veya
kullanıcı verisi gösterilmez.

## Hata sınırları

Tipli kullanıcı sonuçları PLAN `14.4.5`'te sayılıdır. Beklenmeyen
`IllegalStateException`, `NullPointerException` ve diğer invariant/programlama
hataları **"bozuk yedek" gibi maskelenmez** — §21'in XLSX için koyduğu kuralın
aynısı.

## Dilim 1'de uygulanan hâli

```text
domain/backup/BackupRows.kt          15 tablonun @Serializable kayıt tipleri
domain/backup/BackupDocument.kt      BackupData, BackupEnvelopeV1, backupJson,
                                     canonicalBackupDataJson, backupDocumentOf
domain/backup/DatabaseBackupExporter.kt  BackupSnapshot, BackupSource, exporter
domain/backup/Sha256.kt              expect fun sha256Of(ByteArray): String
desktopMain/domain/backup/DesktopSha256.kt  actual + paylaşılan lowerCaseHex
data/database/dao/BackupDao.kt       15 sıralı okuma + @Transaction snapshot()
data/repository/BackupStore.kt       entity → yedek kaydı eşlemesi, PRAGMA user_version
```

Verilen ve testle sabitlenen biçim kararları:

- **Pretty-print YOK, tek satır compact.** Sebebi okunabilirlik tercihi değil
  checksum'dır: gömülü `data` ile hash'lenen `data` **bayt bayt aynı** olmak
  zorundadır ve girintili yazımda iç içe nesne ile tek başına yazılmış nesne
  farklı çıkar. `BackupDocumentTest` bunu `"data":` + kanonik metin içermesiyle
  çiviler.
- `createdAt` `Instant.toString()` ile ISO-8601/UTC; satır zamanları epoch millis.
- Alan adları camelCase ve **elle beyan edilmiştir**; Room sütun adından
  türetilmez. Eşleme `BackupManifest` (desktopTest) ile çivilenir.
- `BackupData` alan sırası = PLAN 14.4.2 restore sırası; dosya, uygulanacağı
  sırayla okunur.

Sıralama **tek yerde**, DAO sorgularının `ORDER BY`'ında yapılır; Kotlin tarafında
ikinci bir sıralama yoktur. PLAN 14.4.2'nin dört tabloda kullandığı anahtar
(`cell_segments`, `task_colors`, `task_stages`, `draft_task_colors`) birincil
anahtar değil **unique index'li iş anahtarıdır**; her biri yine tam sıra verir.

## Dilim 2'de uygulanan hâli

```text
ui/navigation/Screen.kt               Screen.Settings (PLAN 12.1 sırasında SON)
ui/feature/settings/SettingsScreen.kt Ayarlar ekranı + Yedekleme bölümü
ui/feature/settings/BackupController.kt / BackupScreenState.kt
domain/backup/BackupFile.kt           BackupFailure, BackupException,
                                      BackupFileGateway/Handle, ad kuralları
platform/backupfiles/                 AwtBackupFilePicker, DesktopBackupFileGateway
platform/files/AtomicFileWriter.kt    exportfiles'tan TAŞINDI ve genelleştirildi
```

Kullanıcı akışı ve sırası:

```text
Yedek oluştur → hedef seçimi → (varsa) üzerine yazma onayı
→ TEK snapshot okuması → kanonik JSON baytları
→ aynı dizinde geçici dosya → tam yazma → ATOMİK taşıma → başarı
```

Hedef seçilmeden veritabanı **okunmaz**; onay reddedilirse ne sorgu çalışır ne de
bayt yazılır. Yazma `document.json.encodeToByteArray()` ile, Dilim 1'in checksum
aldığı baytların **ta kendisiyle** yapılır; platform varsayılan charset'i araya
girmez.

Önerilen ad: `pnp-yedek-YYYY-AA-GG.json`. Tarih kullanıcının kendi takvim
gününden (`localMomentOf`), sayı biçimleyici değil **padStart** ile üretilir; aynı
gün her locale'de aynı adı verir ve adda saat, oyun adı veya makine bilgisi yoktur.
Uzantısız ad `.json` alır, `.json` olan aynen kalır, başka uzantı **reddedilir**
(CSV'nin `.csv` kuralının aynısı).

### Atomik yazma sözleşmesi — iki özellik, tek yazıcı

`AtomicFileWriter` artık `platform.files` altında ve iki özellik onu paylaşıyor.
Tipsiz `AtomicWriteFailure` beş şey söyler; her özellik kendi cümlesine çevirir:

```text
NOT_WRITABLE          → CSV: NOT_WRITABLE      · Yedek: NOT_WRITABLE
TEMPORARY_FILE_FAILED → CSV: NOT_WRITABLE      · Yedek: TEMPORARY_FILE_FAILED
TARGET_UNAVAILABLE    → CSV: WRITE_FAILED      · Yedek: TARGET_UNAVAILABLE
WRITE_FAILED          → CSV: WRITE_FAILED      · Yedek: WRITE_FAILED
NOT_ATOMIC            → CSV: NOT_ATOMIC        · Yedek: NOT_ATOMIC
```

CSV iki ayrımı geri katlar, çünkü kullanıcının yapabileceği şey ikisinde de aynı;
CSV'nin dışarıdan görünen davranışı **değişmedi**. Yedek ayrımları korur, çünkü
"başka klasör seç" ile "diski geri tak" farklı işlerdir.

### Durum modeli ve eşzamanlılık

`BackupScreenState`: `Idle` · `ChoosingDestination` · `ConfirmingOverwrite` ·
`Preparing` (veritabanı okunuyor) · `Writing` (dosya yazılıyor) · `Saved` ·
`Failed`. Paralel boolean yok; imkânsız durum kurulamaz.

- Çift tıklama / tekrarlanan Enter **tek** pencere ve **tek** yazma üretir.
- Üzerine yazma onayı **sorulduğu handle'a aittir**; kullanıcı başka dosya
  seçerse eski onay uygulanacak bir şey bulamaz.
- Açık soru arkasındaki düğme çalışmaz.
- Yazma sürerken ekrandan ayrılmak yazmayı yarıda bırakmaz.
- Başarıdan ve hatadan sonra yeni yedek alınabilir.
- Odak, kapanan her yüzeyden sonra `Yedek oluştur` düğmesine döner
  (`focusRecall`, bir kez tüketilir).

### Kullanıcıya gösterilen hata sınırları

Dokuz `BackupFailure` değerinin her biri **kendi** Türkçe cümlesine exhaustive
eşlenir (`messageFor`, `else` yok): hedef seçilemedi · uzantı `.json` değil ·
yazılamıyor · geçici dosya · hedefe ulaşılamıyor · yazma başarısız · atomik
taşıma yok · veritabanı okunamadı · belge hazırlanamadı. Beklenmeyen
`IllegalStateException`/`NullPointerException` **maskelenmez**; yalnız
`SQLiteException` ve `SerializationException` kullanıcı sonucuna çevrilir.

## Dilim 3'te uygulanan hâli

```text
domain/backup/restore/BackupProblem.kt      25 tipli ret nedeni + BackupPlace/Rejection
domain/backup/restore/BackupInput.kt        BackupInput/BackupBytes, 64 MiB'lık okuma
domain/backup/restore/BackupText.kt         sıkı UTF-8, BOM, JSON ön taraması
domain/backup/restore/DocumentShape.kt      serializer descriptor'larından yapı denetimi
domain/backup/restore/EnvelopeGate.kt       zarf, sürüm kapıları ve checksum
domain/backup/restore/BackupValues.kt       15 tablonun değer ve alan kuralları
domain/backup/restore/BackupGraph.kt        21 referans + 25 benzersiz anahtar + sıralar
domain/backup/restore/ValidatedBackup.kt    doğrulanmış sonuç + güvenli özet
domain/backup/restore/UntrustedBackupReader.kt  boru hattının kendisi
desktopMain/platform/backupfiles/PathBackupInput.kt  dosyayı bayt olarak sunar
desktopMain/data/database/TemporaryBackupProbe.kt    geçici Room v8 denemesi
```

### 64 MiB, UTF-8 ve BOM

```text
sınır          64 MiB = 67_108_864 bayt.  PLAN 14.4.1 "en fazla 64 MiB" dediği
               için tam sınır KABUL, bir bayt fazlası RET; ikisinin de testi var
iki kez        bildirilen boyut dosya AÇILMADAN, sonra okunurken tekrar sayılır.
               Bildirilen boyuta güvenilmez: kontrol ile okuma arasında dosya
               büyüyebilir. En fazla limit+1 bayt kabul edilir, sınırsız
               readBytes() YOKTUR ve stream bütün yollarda kapatılır
boş dosya      MALFORMED_JSON değil, kendi sonucu (EMPTY_FILE)
UTF-8          decodeToString(throwOnInvalidSequence = true); toleranslı okuma
               ve platform charset'i YOK. Bozuk bayt onarılmaz, dosya reddedilir
BOM            baştaki BİR UTF-8 BOM atlanır — `CsvParser` da atlar; bir metin
               düzenleyicisinden geçmiş yedek üç bayt yüzünden reddedilmez.
               İKİNCİ BOM atlanmaz ve belge JSON olmaz. Yazıcı BOM yazmaz
UTF-16/32      BOM'lu ise INVALID_UTF8, BOM'suz ise MALFORMED_JSON; ikisi de ret
```

### Tekrarlanan anahtar ve yuvalama

`kotlinx.serialization` bir nesneyi map'e okur ve map anahtar başına tek değer
tutar; `{"formatVersion":1,"formatVersion":99}` **99 diyen sıradan bir belge
olarak** gelir. Bu bir testle kayda geçmiştir. PLAN 14.4.1 tekrarlanan anahtarı
reddettirdiği için karar, tekrarın atılmasından **önce** verilmek zorundadır.

Bu yüzden ayrıştırmadan önce dar amaçlı bir metin taraması çalışır
(`scanJsonText`). İkinci bir JSON parser **değildir**: sayıya, alanın yerine veya
belgenin geçerliliğine karar vermez. Yalnız

```text
tekrarlanan object key   escape'ler ÇÖZÜLEREK karşılaştırılır ("\u0066ormat" = "format");
                         her nesne kendi kapsamında; farklı nesnelerde aynı key SERBEST
yuvalama derinliği       en fazla 32 (biçim en fazla 4 seviye yuvalar);
                         parse başlamadan uygulanır, stack overflow'a fırsat kalmaz
string kuralları         string içindeki { } : , taramayı bozmaz; ham kontrol
                         karakteri, bozuk escape ve eşleşmeyen surrogate ret
belgeden sonrası         en dış değer kapandıktan sonra boşluk dışında hiçbir şey
```

`kotlinx.serialization` internal API'si kullanılmaz; her şey public API üzerinden.

### Zarf, sürüm ve checksum kapıları

Sıra kesindir ve her biri ayrı tipli sonuç verir:

```text
format != "pnp-tracker-backup"    WRONG_FORMAT
formatVersion > 1 / < 1           FORMAT_TOO_NEW / FORMAT_TOO_OLD
sourceSchemaVersion > 8 / < 8     SCHEMA_TOO_NEW / SCHEMA_TOO_OLD
createdAt kanonik UTC değil       INVALID_CREATED_AT  (parse edilir VE geri yazılıp
                                  aynı metin olduğu doğrulanır)
dataSha256 64 küçük harf hex yok  MALFORMED_CHECKSUM  (büyük harf hash de buraya düşer)
data yeniden hash'i uyuşmuyor     CHECKSUM_MISMATCH
```

`appVersion` **kapı değildir**: farklı, çok yeni veya boş olması hiçbir yedeği
reddettirmez (PLAN 14.4.1). Eski format/şema için yükseltme zinciri
**uydurulmamıştır**; `1`/`8` tek bilinen çifttir.

Checksum, Dilim 1'in kanonik yazıcısı ve `sha256Of`'u ile yeniden hesaplanır —
ikinci bir implementasyon veya farklı bir projection yoktur. Mantıksal `data`
üzerinden olduğu için alanları başka sırayla yazılmış bir dosya **aynı** yedektir
ve kabul edilir; tek karakterlik veri değişikliği ise reddedilir. Bilinmeyen ve
tekrarlanan alanlar checksum'dan **önce** reddedilir. Checksum uyuşmazlığında
geçici veritabanı **oluşturulmaz** (testle sabit).

### Değer, graf ve domain doğrulaması

Doğrulama aşamalı ve açıktır: `parse edilmiş DTO → yapı → değer → kimlik/enum →
graf → domain invariant → ValidatedBackup`. **Hiçbir şey onarılmaz** — trim,
normalize, clamp, varsayılanla doldurma ve sessizce atlama yoktur (PLAN 14.4.2).

Kurallar entity `init` bloklarının aynısıdır, fakat entity kurularak değil
**burada tekrar söylenerek** denetlenir. İki sebep: başarısız bir `require`
mesajında kullanıcının verisi durur ve o metin dışarı çıkamaz (PLAN 14.4.5); ve
"yedek yanlış" ile "kod yanlış" farklı olgulardır. Son sözü yine entity'ler söyler:
geçici veritabanı her satırı Room üzerinden geri okur, yani burada atlanmış bir
kural orada yakalanır.

Graf doğrulaması **bellekte**, set/map ile, O(n)'dir; satır başına sorgu yoktur.
Denetlenen sözleşme elle beyan edilir ve `BackupContractCoverageTest` onu commit'li
`8.json` ile karşılaştırır:

```text
21 yabancı anahtar   8.json'daki her FK; nullable olanı ayrıca işaretli
25 benzersiz anahtar 15 primary key + 10 unique index; nullable kolonlarda
                     SQL'in kuralı gibi NULL'lar muaf
                     (cell_segments.task_id → bir Task tek TASK segmentine bağlanır;
                      game_cells (game_id, column_type); draft_tasks.materialized_task_id)
boşluksuz sıralar    cell_segments.order_index, task_colors.slot_index,
                     task_stages.order_index, draft_task_colors.slot_index
                     her grupta 0..N-1
```

Gelecekte şemaya bir FK eklenip validator unutulursa test kırılır — sessizce
geçmez.

### Geçici Room v8 denemesi

```text
yol             TemporaryBackupProbe DIŞARIDAN YOL ALMAZ; kendi geçici dizinini yapar.
                Dizin sistem temp'i altında değilse veya uygulamanın veri dizininin
                içindeyse IllegalStateException ile REDDEDER (kullanıcıya gösterilen
                bir "bozuk yedek" sonucu DEĞİL — çağıran hatalıdır)
handle          üretim AppDatabase'i içeriye verilemez; sınıf kendi açar, kendi kapatır
temizleme       15 tablo TERS FK sırasında DELETE — Room'un yeni veritabanına ektiği
                12 tohum renk dâhil. Merge değil replace (PLAN 14.4.3)
yazma           PLAN 14.4.2 sırasında, tablo başına TEK prepared statement, satır
                başına 0 SELECT
transaction     tek immediate transaction; içinde PRAGMA defer_foreign_keys = TRUE
                ve commit'ten önce AÇIK PRAGMA foreign_key_check
geri okuma      BackupStore ile kanonik yeniden okuma: data ve dataSha256 kaynakla
                birebir aynı olmalı; PRAGMA user_version == 8. Room satırları
                entity'ler üzerinden kurduğu için bütün init invariant'ları
                burada bir kez daha çalışır
temizlik        db, -wal, -shm ve dizin BAŞARI, HATA ve İPTAL yollarının hepsinde silinir
integrity_check yeni ve boş bir veritabanında sınırlı kanıt taşıdığı için
                ÇALIŞTIRILMAZ; esas kanıt foreign_key_check, entity invariant'ları
                ve kanonik yeniden okumanın eşitliğidir
canlı DB        bu API canlı veritabanında replace SUNMAZ
```

### Tipli ret nedenleri

`BackupProblem` 25 değer taşır ve hepsi birbirinden ayrıdır, böylece Dilim 4 doğru
Türkçe cümleyi seçebilir:

```text
UNREADABLE · EMPTY_FILE · SAFETY_LIMIT · INVALID_UTF8 · MALFORMED_JSON ·
TOO_DEEPLY_NESTED · DUPLICATE_KEY · UNKNOWN_FIELD · MISSING_FIELD · WRONG_TYPE ·
WRONG_FORMAT · FORMAT_TOO_NEW · FORMAT_TOO_OLD · SCHEMA_TOO_NEW · SCHEMA_TOO_OLD ·
INVALID_CREATED_AT · MALFORMED_CHECKSUM · CHECKSUM_MISMATCH · INVALID_ID ·
INVALID_ENUM · INVALID_VALUE · DUPLICATE_RECORD · BROKEN_REFERENCE ·
DOMAIN_INVARIANT · TEMP_VALIDATION_FAILED
```

`BackupRejection` yalnız bir neden ve bir `BackupPlace` taşır. `BackupPlace` iki
alanı da biçimin **kendi sabit sözlüğünden** gelir (bir dizi adı ya da `envelope`
/ `file`, ve kayıt tipinin bir alan adı). İçinde `Throwable` **yoktur**: bir
`Throwable` stack trace üzerinden dosya adlarını ve kütüphane içini taşırdı.
Bilinmeyen bir alanın **adı asla dışarı çıkmaz** — onu dosyayı yazan yazdı.

Yalnız `BackupInputException`, `SerializationException`, `CharacterCodingException`,
`SQLiteException` ve entity'lerin `IllegalArgumentException`'ı tipli sonuca
çevrilir. `IllegalStateException`, `NullPointerException` ve `Error` **olduğu gibi
yükselir**; hiçbir yerde `Throwable` yakalanmaz (testle sabit).

## Dilim 4'te uygulanan hâli

```text
domain/backup/restore/RestoreOutcome.kt      RestoreProblem (6), SafetySnapshot,
                                             BackupRestorer
domain/backup/restore/SafetyBackup.kt        pnp-oncesi-… ad kuralı + SafetyBackupWriter
domain/backup/restore/BackupSourceGateway.kt kaynak dosya seçimi (yalnız BackupInput döner)
data/database/BackupTables.kt                RESTORE_ORDER + replaceEverythingWith;
                                             geçici DB ile canlı DB'nin PAYLAŞTIĞI tek yazıcı
data/database/LiveBackupRestorer.kt          canlı replace transaction'ı
ui/StaleSurfaces.kt                          bayat düzenleme yüzeylerini kapatma sözleşmesi
ui/feature/settings/RestoreScreenState.kt    on durum
ui/feature/settings/RestoreController.kt     akışın kendisi
ui/feature/settings/RestoreSection.kt        Ayarlar'daki eylem, onay ve sonuç
desktopMain/platform/backupfiles/BackupSourceFiles.kt        AWT açma diyaloğu + gateway
desktopMain/platform/backupfiles/DesktopSafetyBackupWriter.kt güvenlik yedeğinin yazımı
```

### Canlı replace transaction sözleşmesi

Geçici veritabanı denemesi ve canlı restore **aynı yazıcıyı** çalıştırır
(`replaceEverythingWith`): aynı temizleme sırası, aynı yazma sırası, aynı
`defer_foreign_keys`, aynı açık `foreign_key_check`. İkinci bir kopya, denemenin
kanıtladığı şey ile restore'un yaptığı şeyin ayrışabileceği yer olurdu.

```text
kabul ettiği   yalnız ValidatedBackup + SafetySnapshot; ikisinin de üretim
               yapıcısı YOK. Dosya, yol, ham DTO veya belge verilemez
transaction    BEGIN IMMEDIATE — yazma kilidi ilk okumadan ÖNCE alınır
1              15 tablo okunur, güvenlik yedeğinin anlık görüntüsüyle karşılaştırılır
2              farklıysa DATA_CHANGED_MEANWHILE; hiçbir DELETE/INSERT yapılmaz
3-6            defer_foreign_keys → ters sırayla DELETE → 14.4.2 sırasıyla INSERT
               → açık foreign_key_check
7-8            15 tablo yeniden okunur; yedekle birebir değilse commit YOK
commit sonrası aynı writer bağlantısı hâlâ tutulurken user_version,
               foreign_key_check ve kanonik data + checksum yeniden doğrulanır
history        restore geçmişe olay YAZMAZ (PLAN 14.4.3)
kimlik/zaman   yeniden üretilmez; `updatedAt == createdAt` eşitliği korunur, yoksa
               geri yüklenen her CONFIRMED batch geri alınamaz olurdu
```

### Güvenlik yedeği ve yarış koruması

```text
ne zaman     son onaydan SONRA, canlı DB'ye tek bayt yazılmadan ÖNCE
nereye       $XDG_DATA_HOME/pnp-tracker/backups/ — kullanıcı seçmez
biçim        manuel yedekle aynı kanonik JSON; aynı AtomicFileWriter
ad           pnp-oncesi-YYYY-AA-GG-SSDDsn.json, çakışmada -2, -3 … (en fazla 16)
çakışma      ad, dosya OLUŞTURULARAK sahiplenilir (Files.createFile) — "var mı"
             diye sorup sonra yazmak arada boşluk bırakır ve atomik taşıma
             ne bulursa üzerine yazar. Var olan bir yedek ASLA değiştirilmez
hata         yazılamazsa restore HİÇ başlamaz; iddia edilen ad silinir, boş
             .json veya .part kalmaz
korunur      hem başarılı hem başarısız restore'dan sonra dosya yerinde durur
yarış        güvenlik yedeğinin BackupData'sı bellekte tutulur ve transaction'ın
             ilk aşamasında canlı 15 tabloyla karşılaştırılır; arada yazılmış bir
             kullanıcı verisi sessizce kaybolamaz
```

### Arayüz, odak ve hata metinleri

```text
durumlar     Idle · ChoosingSource · Validating · Confirming · CreatingSafetyBackup
             · WritingSafetyBackup · Applying · Restored · Rejected · Failed
denetleme    TEK durum ("Yedek denetleniyor…"). Dilim 3 API'si aşamaları tek
             sonuç olarak verir; sahte ilerleme yüzdesi ÜRETİLMEZ
onay         yalnız doğrulama bittikten sonra; odak `Vazgeç` üzerinde başlar,
             `Geri yükle` error rengindedir, Escape yalnız bu yüzeyi kapatır
token        onay, sorulduğu ValidatedBackup'a bağlıdır; başka dosya seçilirse
             eski onay uygulanacak bir şey bulamaz
çift gönderim ikinci tıklama/Enter tek güvenlik yedeği ve tek transaction üretir;
             onaydan sonra yüzey iş bitene kadar kapatılamaz
özet         güvenli dosya adı, alınma zamanı (kullanıcının takviminde) ve
             oyun/görev/renk sayısı. Oyun adı, not, UUID, yol veya SQL YOK
metinler     25 BackupProblem exhaustive olarak 14 Türkçe cümleye, 6 RestoreProblem
             6 ayrı cümleye eşlenir; `else` ve `.name` YOK
tazeleme     restore sonrası Flow'lar yenilenir (Room invalidation), açık
             düzenleme yüzeyleri kapanır, gezinme Ayarlar'da kalır, yeniden
             başlatma gerekmez
```

## Reddedilen alternatifler  *(tekrar önerilmesin)*

```text
merge/birleştirmeli restore        REDDEDİLDİ  çakışma kuralı PLAN'da yok; sessiz veri kaybı
DB dosyası / WAL / SHM takası      REDDEDİLDİ  atomik değil, yan dosya riski, desktop'a özgü
bilinmeyen alanı sessizce yok say  REDDEDİLDİ  bir sonraki export'ta veri kaybı
elle yazılmış JSON parser          REDDEDİLDİ  unicode/surrogate/derinlik yüzeyi çok geniş
varsayılan sıkıştırılmış biçim     REDDEDİLDİ  dosyanın okunabilirliği yedek için güvence
restore history event'i            REDDEDİLDİ  history_events.game_id NOT NULL; ayrıca yedeğin
                                               geçmişiyle çelişirdi
kısmi/yarım restore                REDDEDİLDİ  tek transaction; ya hep ya hiç
güvenlik yedeği başarısızken devam REDDEDİLDİ  kullanıcının geri dönüş yolunu sessizce siler
```

## Dört atomik dilim

```text
1  JSON sözleşmesi, bütün DB snapshot'ı ve deterministik yazıcı .... TAMAM
   kullanıcıya açılan bir şey yok; yalnız okur. Şema değişmedi.
   commit: feat(backup): describe the whole database as one document

2  Manuel yedek dosyası yazma + Ayarlar ekranı ..................... TAMAM
   kullanıcı yedek alabilir; geri yükleme yok. Şema değişmedi.
   commit: feat(backup): save the whole database to a file

3  Güvenilmeyen dosyayı parse etme, doğrulama, geçici DB denemesi ... TAMAM
   doğrulanmış belgeden canlı DB'ye giden yol YOK. Şema değişmedi.
   commit: feat(backup): read a backup file without trusting it

4  Restore öncesi güvenlik yedeği + canlı replace transaction + arayüz  TAMAM
   kullanıcı yedeğini geri yükleyebiliyor. Şema değişmedi.
   commit: feat(backup): put a backup back
```

**Hiçbir ara commit doğrulanmamış veya yarım bir restore yolunu kullanıcıya açmadı.**
Dilim 3'ün bir commit boyunca çağrılmayan üretim API'si bırakması bilinen ve kabul
edilmiş kalıptır; geri alma motoru da (İş 2 / Dilim 2) bilerek bağlanmamıştı (§33 R3).

---

# 25.2 OTOMATİK SNAPSHOT VE DÖNGÜSEL SAKLAMA  *(Faz 3 / İş 4 — TAMAMLANDI)*

Bağlayıcı metin PLAN `14.4.7`–`14.4.13`'tedir. Aşağısı alınan kararların özeti,
gerekçeleri ve **dört dilimin tamamında uygulanan hâlidir**.

## İki tetikleyici, üç artefakt türü

```text
içe aktarma onayı → 1 JSON snapshot
migration         → 1 SET = ham .db + yürütülmüş .json   (ikisi bir bütün)
```

## İçe aktarma öncesi snapshot

```text
eşik            YOK. "Büyük içe aktarma" ifadesi PLAN'dan KALDIRILDI
XLSX / CSV      AYRIM YOK; ikisi de aynı taslak borusundan ve aynı
                confirmDraftBatch transaction'ından geçer
nerede          taslak oluşturmadan önce DEĞİL; domain verisini değiştiren
                confirmDraftBatch çağrısından HEMEN ÖNCE
biçim           normal kanonik formatVersion 1 JSON, 15 tablo
geri yükleme    Ayarlar → Yedekten geri yükle ile normal biçimde açılabilir
ekran           onay ekranı, önce otomatik yedek alınacağını TEK anlaşılır
                cümleyle söyler
history         olay YAZMAZ
çift gönderim   TEK snapshot, TEK confirmation
```

Eşik neden kaldırıldı: hangi içe aktarmanın "büyük" olduğuna dair savunulabilir
bir sayı yok, ve yanlış seçilmiş bir eşik tam da korunması gereken içe aktarmayı
korumasız bırakır. 25 veya 50 görev gibi sayılar **reddedildi**.

## Snapshot ↔ confirmation yarış modeli

Restore'un (`§25.1`) güvenlik modelinin eşdeğeri uygulanır:

```text
1  snapshot'ın değişmez BackupData + dataSha256 değeri bellekte tutulur
2  snapshot atomik yazılır VE gerçek okuyucuyla yeniden doğrulanır
3  confirmation transaction'ının İLK aşaması canlı DB'yi aynı kanonik
   sözleşmeyle yeniden okur
4  DB snapshot'tan sonra değişmişse HİÇBİR domain yazımı yapılmadan reddedilir
5  kullanıcıya verilerin bu sırada değiştiği ve tekrar denemesi söylenir
6  oluşturulmuş snapshot geçerli bir yedek olarak kalabilir
7  transaction'ın yazma kilidi alındıktan sonra başka writer araya giremez
```

**Global mutation barrier yerine transaction içi yeniden doğrulama + fail closed
seçilmiştir.** Teknik olarak eşdeğer veya daha güçlü bir çözüm kanıtlanırsa
uygulanabilir; **güvence sessizce kaldırılamaz.**

## Migration öncesi çift artefakt

```text
1  ham .db    migration ÖNCESİNDEKİ eski şemanın tutarlı SQLite klonu
              → migration KODUNUN KENDİSİNDEKİ hataya karşı korur
              → üretilirken migration kodunu hiç çalıştırmaz
2  .json      bu klonun AYRI çalışma kopyası gerçek migration zinciriyle v8'e
              yürütüldükten sonra üretilen kanonik yedek
              → Ayarlar ekranından normal biçimde geri yüklenebilir
```

**İkisi birlikte tutulur.** Yalnız JSON üretmek, korunmak istenen migration
koduna bağımlı olurdu; yalnız ham `.db` üretmek, "kullanıcı otomatik yedeklerden
verisini geri yükleyebilir" ölçütünü (PLAN `18.`) karşılamazdı. **Set ancak iki
eş de doğrulandığında başarılı sayılır.**

```text
ham klon     SQLite'ın tutarlı snapshot mekanizmasıyla üretilir
             DB + -wal + -shm'yi sırayla kopyalamak YASAK (atomik değil)
             VACUUM INTO kullanımına izin verildi; gerçek davranış uygulamada
             TESTLE doğrulanmalı, varsayılmamalı
ham doğrulama  SQLite biçim başlığı + user_version + integrity_check + FK
json doğrulama gerçek UntrustedBackupReader + TemporaryBackupProbe hattı
ham .db        Ayarlar'daki JSON restore seçicisinde GÖSTERİLMEZ
               (seçici .json süzer); uygulama içinden doğrudan restore yolu
               bu işte OLUŞTURULMAZ
```

## Açılış kapısı

```text
 1  instance/açılış kilidi alınır
 2  DB'nin varlığı ve user_version'ı Room AÇILMADAN belirlenir
 3  DB yoksa normal oluşturma yolu; snapshot yok
 4  sürüm 8 ise migration snapshot'ı OLUŞTURULMAZ
 5  sürüm 1..7 ise snapshot seti oluşturulur
 6  8'den büyük / desteklenmeyen / bozuk ise DB AÇILMAZ, güvenli hata gösterilir
 7  ham klon tamamlanıp doğrulanmadan çalışma kopyası oluşturulmaz
 8  çalışma kopyası gerçek migration zinciriyle v8'e yürütülür
 9  yürütülmüş kopyadan kanonik JSON üretilir ve gerçek hattan geçirilir
10  iki eş de doğrulanmadan set BAŞARILI SAYILMAZ
11  set başarılı olmadan gerçek DatabaseFactory kullanıcı DB'sini AÇAMAZ
12  klon migration'ı veya JSON üretimi başarısızsa gerçek DB HİÇ migrate edilmez
13  instance kilidi, snapshot ve gerçek açılış/migration bitene kadar tutulur
14  geçici klon, çalışma DB'si, -wal, -shm ve .part bütün yollarda temizlenir
```

Çalışma kopyasının migration'ı başarılı fakat **gerçek** migration başarısızsa,
her iki artefakt da korunur ve kullanıcı güvenli açılış hata penceresini görür.
**Bu pencere İş 4 kapsamındadır** ve Faz 3 / İş 7'ye bırakılmaz.

## Adlandırma

```text
pnp-otomatik-import-YYYY-AA-GG-SSDDsn.json
pnp-otomatik-migration-v<eski>-v<yeni>-YYYY-AA-GG-SSDDsn.db
pnp-otomatik-migration-v<eski>-v<yeni>-YYYY-AA-GG-SSDDsn.json
pnp-oncesi-YYYY-AA-GG-SSDDsn.json        (restore öncesi, §25.1)
pnp-yedek-<tarih>.json                   (manuel, hedefi kullanıcı seçer)
```

Çakışma, atomik sahiplenme kalıbıyla çözülür (`Files.createFile`, sonra `-2`,
`-3`); tek yeri `ClaimedNameWriter`'dır. **Bir migration setinin iki eşi AYNI
soneki taşır** (`…-2.db` + `…-2.json`); ikisi adlarından eşleştirilebilir
olmalıdır, ve bunu `claimSetNames` iki adı aynı anda alarak sağlar — ikincisi
doluysa ilki geri verilir.

Ada **girmeyenler**: kullanıcı adı, makine adı, oyun adı, gerçek içe aktarma
dosyasının adı, herhangi bir veri içeriği.

## Üç ayrı kota  *(türler arası ortak kota REDDEDİLDİ)*

```text
1  içe aktarma öncesi JSON snapshot'ları
2  restore öncesi pnp-oncesi-* JSON güvenlik yedekleri
3  migration snapshot SETLERİ   (.db + .json = TEK set)
```

Her tür için ayrı ayrı `automaticBackupCount` kadar en yeni kayıt/set tutulur.
**Bir türdeki yoğunluk başka türün yedeklerini silemez:** art arda yapılan içe
aktarmalar kullanıcının restore dönüş yolunu tahliye edemez. Manuel
`pnp-yedek-*` dosyaları **hiçbir otomatik kotaya girmez.**

## Rotation davranışı

```text
sıra         yeni snapshot/set atomik tamamlanıp DOĞRULANMADAN eski dosya silinmez
hedef        yalnız kendi türü + sahipliği KANITLANMIŞ dosyalar
JSON sahiplik  beklenen ad kalıbı + normal dosya + symlink değil +
               doğrulanmış `pnp-tracker-backup` zarfı
set sahiplik   beklenen EŞLEŞMİŞ adlar + iki normal dosya + geçerli SQLite
               başlığı/metadata + doğrulanmış JSON
YETMEZ       yalnız ad öneki eşleşmesi silme yetkisi VERMEZ
dokunulmaz   manuel dosya, bilinmeyen dosya, symlink, dizin, FIFO, .part,
             bozuk/eksik eşli set (rotation adayı sayılmaz; güvenli hata/inceleme)
sıralama     dosya adındaki kanonik timestamp + sonek; mtime'a GÜVENİLMEZ
             (kopyalama/eşitleme mtime'ı değiştirir, adı değiştirmez)
durum        rotation durum tutmaz → crash/retry idempotent
hata         silme hatası yeni snapshot'ı, importu veya migration'ı ENGELLEMEZ
             ve kullanıcıya blocking hata göstermez
ayar azalışı dosyalar HEMEN silinmez; yeni değer atomik kaydedilir, temizlik
             BİR SONRAKİ başarılı otomatik snapshot'tan sonra uygulanır
```

## `settings.json` sözleşmesi

```json
{
  "formatVersion": 1,
  "automaticBackupCount": 7
}
```

```text
yer            $XDG_CONFIG_HOME/pnp-tracker/settings.json, düz UTF-8 JSON
aralık         default 7 · minimum 1 · maksimum 50
0              GEÇERSİZ — otomatik koruma kapatılamaz
bilinmeyen alan YOK SAYILIR (yedeğin katı politikasının bilinçli TERSİ:
               ayar dosyası kullanıcı verisi taşımaz, katı olmak eski sürüme
               dönen kullanıcının uygulamasını açılmaz yapardı)
bozuk dosya    runtime default 7; uygulama AÇILIR; Ayarlar'da varsayılanın
               kullanıldığı AÇIKÇA gösterilir; dosyanın ÜZERİNE YAZILMAZ
yazma          atomik; başarısızsa eski dosya bayt bayt kalır ve runtime değeri
               değişmez
eşzamanlılık   aynı process içindeki yazımlar mutex/tek controller ile sıralanır
UI             yalnız 1..50 kabul eder
YAZILMAZ       yol, DB hash'i, kullanıcı içeriği
kapsam         restore/yedek kapsamındaki 15 TABLOYA EKLENMEZ — bir geri yükleme
               kullanıcının ayarını değiştirmez; ayrıca açılış kapısının ayara
               DB açılmadan önce ulaşması gerekir
```

Bu işte **başka ayar alanı eklenmez.**

## Hata semantiği

```text
FAIL CLOSED  import snapshot üretilemez/yazılamaz/doğrulanamaz → confirmation
             HİÇ başlamaz; batch DRAFT kalır; görev, segment, completion ve
             history YAZILMAZ
             migration ham klon veya JSON üretilemez/yazılamaz/doğrulanamaz →
             gerçek Room DB AÇILMAZ, migration BAŞLAMAZ
             snapshot sonrası DB değişmişse import confirmation YAZMADAN reddedilir
             snapshot seti ile gerçek migration arası koruma doğrulanamazsa
             migration BAŞLAMAZ
FAIL OPEN    başarılı yeni snapshot sonrasında eski otomatik yedeğin
             SİLİNEMEMESİ import veya migration'ı ENGELLEMEZ
KORUNUR      snapshot başarılı olup confirmation sonradan düşerse snapshot KALIR
AYAR         okuma/parse hatası → default 7 ile açılır + Ayarlar'da uyarı
             yazma hatası → eski dosya ve runtime değeri korunur
```

## Kullanıcı bildirimi

```text
gösterilir     her import confirmation onayında otomatik yedek alınacağı
               (tek anlaşılır cümle)
gösterilir     snapshot failure → import ekranında güvenli Türkçe hata
gösterilir     startup migration snapshot/migration failure → GERÇEK hata penceresi
               ("ana DB'ye geçilmedi, veri değiştirilmedi")
gösterilmez    ham exception, SQL, UUID, mutlak yol, kullanıcı verisi, gerçek DB
               metadata'sı
gösterilmez    rotation failure (blocking hata YOK)
gösterilmez    Ayarlar'da son otomatik snapshot zamanı
eklenmez       "Yedek klasörünü aç" eylemi
yazılmaz       otomatik snapshot history event'i
```

## Reddedilen alternatifler  *(tekrar önerilmesin)*

```text
yalnız ham migration snapshot          REDDEDİLDİ  uygulama içinden restore
                                                   ölçütünü karşılamaz
yalnız yürütülmüş migration JSON       REDDEDİLDİ  korunmak istenen migration
                                                   koduna bağımlı olurdu
25 / 50 görevlik "büyük import" eşiği  REDDEDİLDİ  savunulabilir bir sayı yok
yalnız büyük importu yedeklemek        REDDEDİLDİ  her onay yedeklenir
kullanıcının kapatabildiği snapshot    REDDEDİLDİ  0 geçersiz; koruma kapatılamaz
türler arası ortak kota                REDDEDİLDİ  import yoğunluğu restore dönüş
                                                   yolunu tahliye ederdi
ayar değerini Room tablosunda tutmak   REDDEDİLDİ  restore ayarı da değiştirirdi;
                                                   açılış kapısı DB'den önce okur
sayı azalınca anında dosya silme       REDDEDİLDİ  ayar değiştirmek yıkıcı olmamalı
rotation hatasında ana işlemi durdurma REDDEDİLDİ  kullanıcı verisi risk altında değil
snapshot başarısızken devam etmek      REDDEDİLDİ  fail closed
startup migration hatasını İş 7'ye     REDDEDİLDİ  hata penceresi İŞ 4 kapsamında
  bırakmak
global mutation barrier                SEÇİLMEDİ   transaction içi yeniden doğrulama
                                                   tercih edildi; eşdeğer/daha güçlü
                                                   bir çözüm kanıtlanırsa uygulanabilir
cp ile DB + -wal + -shm kopyalama      REDDEDİLDİ  atomik değil, tutarlı snapshot vermez
```

## Dört atomik dilim

```text
1  Otomatik adlar, sahiplik ve tür başına rotation motoru ......... TAMAM
   hiçbir tetikleyici bağlı değil; kullanıcıya açılan bir şey yok
   commit: feat(backup): keep a bounded number of automatic backups

2  Sürümlü settings.json + saklama sayısı Ayarlar UI'sı ........... TAMAM
   ayar gerçekten kaydedilir ve gerçekten uygulanır; rotation restore
   öncesi güvenlik yedeğinin ardından bu sayıyla çalışır
   commit: feat(settings): let the number of automatic backups be chosen

3  Her XLSX/CSV confirmation öncesi JSON snapshot + yarış koruması  TAMAM
   her onay yedeklenir, dosya doğrulanır, yarış transaction içinde kapatılır
   commit: feat(import): save the data before an import changes it

4  Migration öncesi ham DB + yürütülmüş JSON seti + açılış kapısı . TAMAM
   kapı yazıldı; korumasız migration artık kod tarafından imkânsız
   commit: feat(backup): save the database before a migration changes it
```

Dilimler **bu sırayla** uygulanır: Dilim 3 ve 4, Dilim 1'in adlarını ve
Dilim 2'nin sayısını kullanır. *(Dilim 4 bitene kadar gerçek uygulamanın normal
kullanıcı XDG'siyle açılmaması kuralı buradaydı; kural artık kalktı ve yerini
açılış kapısının kendisine bıraktı — §0, §33 R10.)*

Hiçbir ara commit: korumasız migration başlatmaz · doğrulanmamış dosyaya
"snapshot alındı" demez · rotation ile kullanıcı dosyası silmez · kalıcılığı
olmayan ayar UI'si açmaz · snapshot başarısızken büyük importu sürdürmez.

## İş 4 / Dilim 1'de uygulanan hâli

```text
domain/backup/BackupStamp.kt                    backupStampOf + isPlausibleStamp;
                                                HER otomatik adın paylaştığı tek damga
domain/backup/retention/AutomaticBackupNames.kt AutomaticBackupKind (3),
                                                AutomaticBackupName, ad üreticileri
                                                ve KATI ad çözümleyici
domain/backup/retention/BackupOwnership.kt      beginsLikeABackupDocument /
                                                beginsLikeADatabaseOfVersion (64 bayt)
domain/backup/retention/AutomaticBackupRotation.kt  BackupDirectory arayüzü,
                                                InspectedBackupFile, OwnedBackup,
                                                RotationOutcome, rotateAfter
desktopMain/platform/backupfiles/DesktopBackupDirectory.kt  tek dosya sistemi teması
```

`SafetyBackup.kt` artık kendi damgasını üretmiyor, `backupStampOf`'u çağırıyor.
Üretilen ad birebir aynı; ikinci bir damga implementasyonu, üç türün birbirine
karşı sıralanamaması demek olurdu.

### Sahiplik iki bağımsız kanıt ister

```text
1  AD      tam kalıp eşleşmesi — önek DEĞİL. `-1` ve `-02` gibi hiç yazılmayan
           sonekler, dolgusuz damga, `.part`, `(1)` kopyası, `v0`/`v03`, ay 13,
           saniye 60 … hepsi REDDEDİLİR (null döner ve rotation onları hiç görmez)
2  İÇERİK  JSON  → dosya, kanonik yazıcının açılışıyla başlamalı:
                   {"format":"pnp-tracker-backup","formatVersion":<rakam>
           .db   → SQLite sihirli baytları + geçerli sayfa boyutu +
                   user_version ADDAKİ v<eski> ile AYNI olmalı
```

İkinci kanıt için dosyanın **ilk 64 baytı** okunur; tamamı asla okunmaz.
Ayrıştırma yapılmaz: bir yedeği tamamen okuyup checksum'ını doğrulamak geri
yüklemeden önce doğrudur, eskisini silmeden önce fazladır. Sahipliği
kanıtlanamayan dosya "bozuk" sayılmaz, **yerinde bırakılır**.

`.db`'nin `user_version`'ının adındaki sürümle uyuşma zorunluluğu, Dilim 4'e
verilmiş bir sözleşmedir: migration ham kopyası, alındığı şema sürümünü korur.
Uyuşmazlık güvenli yöne düşer — set rotation adayı sayılmaz, sonsuza kadar
kalır; disk harcar, dosya kaybettirmez.

### Dosya tipi ve yol sınırları

```text
symlink / dizin / FIFO / normal olmayan her şey  →  ordinaryFile = false
                                                    header okunmaz, ASLA silinmez
NOFOLLOW_LINKS                                   →  hem listelemede hem silmede
silme argümanı                                   →  YALNIZ ad; "../x", mutlak yol
                                                    ve "alt/klasor/x" reddedilir
                                                    (çözümlenmiş yolun parent'ı
                                                     backups/ olmak ZORUNDA)
okunamayan dizin / okunamayan dosya              →  boş liste / boş header,
                                                    yani "bizim olduğu gösterilemedi"
```

### Silmeden önce yazıldığının kanıtı — yapısal

`rotateAfter(justWritten, keep)` yeni yedeğin **set adını** ister ve onu kendi
sahiplik testinden geçmiş yedekler arasında **bulamazsa hiçbir şey silmez**
(`refused = true`). Böylece PLAN 14.4.11'in "yeni snapshot atomik tamamlanıp
doğrulanmadan eski dosya silinmez" kuralı, hatırlanması gereken bir kural
olmaktan çıkıp **API'nin kırılamayacağı bir özelliği** olur: hiçbir şey yazmamış
bir çağıranın verecek set adı yoktur, uydurduğu ad da hiçbir şey sildirmez.

### Sıralama ve kotalar

```text
sıra anahtarı  (damga ↓, sonek SAYI olarak ↓, set adı ↓) — mtime KULLANILMAZ
sonek          sayı olarak karşılaştırılır: -10, -2'den YENİDİR
                (metin olarak sıralansaydı en yeni dosya silinirdi)
kota           üç tür AYRI: import · pnp-oncesi · migration SETİ
migration      .db + .json = TEK yedek; birlikte sayılır, birlikte silinir
eksik eş       set sayılmaz; iki yarım da yerinde bırakılır
manuel         pnp-yedek-* ad çözümleyiciden geçmez → rotation onu hiç görmez
keep           1..50 parametresi; gerçek automaticBackupCount Dilim 2'de bağlanır
```

Bir tur **her üç türü** kendi kotasına indirir (yalnız yazılan türü değil): PLAN
14.4.12 küçültülmüş sayının "bir sonraki başarılı otomatik snapshot'tan sonra"
uygulanmasını istiyor, ve migration seti bir kurulum ömründe bir kez yazıldığı
için yalnız kendi türü tetiklense asla küçülmezdi.

### Hata davranışı

```text
silinemeyen dosya   couldNotRemove'a yazılır, ATILMAZ; ana işlem etkilenmez
                    (PLAN 14.4.13 fail open)
yarım kalan tur     rotation durum tutmaz; sonraki tur dizini yeniden okur ve
                    tamamlar — crash/retry idempotent
yerleşmiş dizin     ikinci tur hiçbir şey silmez
keep aralık dışı    IllegalArgumentException (programlama hatası, kullanıcı hatası değil)
```

### Dilim 1'in bilerek YAPMADIKLARI

```text
settings.json okuma/yazma                 Dilim 2
Ayarlar UI değişikliği                    Dilim 2
XLSX/CSV onay akışına bağlanma            Dilim 3
migration açılış kapısı / gerçek snapshot Dilim 4
otomatik yedeği YAZAN kod                 Dilim 3 ve 4 (bu dilim yalnız SİLER)
```

`Main.kt` değişmedi; `AutomaticBackupRotation` ve `DesktopBackupDirectory` hiçbir
üretim yolundan çağrılmıyor. Bu, İş 2 / Dilim 2 ve İş 3 / Dilim 3'teki kabul
edilmiş kalıptır (§33 R3).

---

## İş 4 / Dilim 2'de uygulanan hâli

```text
domain/settings/AutomaticBackupSettings.kt        belge, settingsJson, dört
                                                  SettingsProblem, iki
                                                  SettingsWriteFailure, SettingsStore,
                                                  settingsIn / settingsDocumentFor
domain/backup/retention/AutomaticBackupHousekeeping.kt  AutomaticBackupHousekeeping +
                                                  SettingsDrivenHousekeeping
ui/feature/settings/RetentionScreenState.kt       Loading · Ready · Saving · Failed
ui/feature/settings/RetentionController.kt        akışın kendisi
ui/feature/settings/RetentionSection.kt           Ayarlar'daki alan, adımlar ve kayıt
desktopMain/platform/settings/DesktopSettingsStore.kt  tek dosya sistemi teması
```

### Dosya yalnız kaydetmeyle oluşur

```text
okuma                 dosya YOKSA varsayılan 7 ve dosya OLUŞTURULMAZ
ekranı açmak          yazmaz
yedek almak           yazmaz
yedek okumak          yazmaz
restore               yazmaz  (housekeeping sayıyı her seferinde okur)
rotation              yazmaz
kullanıcı Kaydet'e basar → dosya ATOMİK olarak yazılır; tek yazan yol budur
```

Bu iddiayı Dilim 1'de duran dört test koruyordu. Silinmediler: her biri artık
**daha güçlü** bir şey söylüyor, çünkü artık dosyanın bir sahibi var.
`RestoreSmokeTest` ve `RetentionSmokeTest` gerçek `DesktopSettingsStore`'u
akışa soktu — üç restore ayar dosyasını okuyan koddan geçiyor ve dosya yine de
oluşmuyor.

### Sözleşme ve okuma politikası

```json
{"formatVersion":1,"automaticBackupCount":7}
```

```text
yer            $XDG_CONFIG_HOME/pnp-tracker/settings.json, düz UTF-8 JSON
aralık         1..50 · varsayılan 7 · 0 GEÇERSİZ (koruma kapatılamaz)
bilinmeyen alan YOK SAYILIR — yedeğin katı politikasının bilinçli TERSİ
tırnaklı sayı  `"7"` yedi olarak okunur (ÖLÇÜLDÜ, varsayılmadı; kendi testi var)
bozuk dosya    varsayılan 7 + Ayarlar'da açık uyarı + dosya OLDUĞU GİBİ kalır
dört sebep     COULD_NOT_READ · NOT_THE_EXPECTED_SHAPE · VERSION_NOT_SUPPORTED
               · VALUE_OUT_OF_RANGE — dördü de AYRI Türkçe cümle
yazma hatası   eski dosya bayt bayt kalır, çalışan değer değişmez
               iki SettingsWriteFailure: NOT_WRITABLE · COULD_NOT_WRITE
               (atomik yazıcının beş ayrımı ikiye indirildi: bir ayar için
                kullanıcının yapacağı iki farklı şey var)
eşzamanlılık   store'da Mutex + controller'da isBusy — iki bağımsız koruma
```

### Ayarın veritabanı dışında olması

Bilinçli ve iki sebebi var: yedeğin kapsamı 15 tablodur, dolayısıyla ayar bir
tabloda dursaydı bir **geri yükleme kullanıcının ayarını da sessizce
değiştirirdi**; ve Dilim 4'ün açılış kapısının bu sayıya veritabanı açılmadan
önce ulaşması gerekiyor.

### Ayar → rotation bağlantısı

```text
SettingsDrivenHousekeeping  sayıyı HER SEFERİNDE okur, hatırlamaz
                            → küçültülmüş sayı bir sonraki otomatik yedekte
                              kendiliğinden geçerli olur; kimsenin haber
                              vermesi gerekmez
nerede çalışır              RestoreController.confirmRestore'un SONUNDA, restore
                            sonucu ekrana yazıldıktan sonra
neden orada                 (1) housekeeping kullanıcıyı bekletmemeli ve sonucu
                            değiştirmemeli; (2) güvenlik yedeğiyle transaction
                            ARASINA konsaydı, o aralıkta yapılan her yazma
                            restore'u reddettirdiği için pencere gereksiz yere
                            genişlerdi
başarısız restore            güvenlik yedeği yine diskte, dolayısıyla retention
                            yine ona uygulanır
silme hatası                 restore'u ve güvenlik yedeğini ETKİLEMEZ (fail open)
```

### Kaydetmek yıkıcı değildir

Sayıyı küçültmek dosyaları o anda **silmez**. Yeni değer atomik kaydedilir ve
fazlası bir sonraki başarılı otomatik yedeğin ardından temizlenir; ekran bunu
kısa bir cümleyle söyler. Bir testi, dolu bir klasörde 7'den 2'ye inip klasörün
bir dosya bile kaybetmediğini, sonraki restore'da ise her türün 2'ye indiğini
gösteriyor.

### Dilim 2'nin bilerek YAPMADIKLARI

```text
XLSX/CSV onay akışına bağlanma            Dilim 3
migration açılış kapısı / gerçek snapshot Dilim 4
otomatik yedeği YAZAN kod                 Dilim 3 ve 4
başka ayar alanı                          kapsam dışı (PLAN 14.4.12)
son snapshot zamanı / klasörü açma        kapsam dışı (PLAN 12.16)
```

*(Dilim 2 biterken `ImportConfirmationStore`, `ImportConfirmationController` ve
`DatabaseFactory` `AutomaticBackupHousekeeping` almıyordu ve
`RetentionAfterRestoreTest` bunu JVM refleksiyonuyla iddia ediyordu. Dilim 3
geldiğinde o test — sessizce silinmek yerine — bilinçli olarak çevrildi:
`ImportConfirmationStore` artık hem housekeeping'i hem snapshot alıcıyı
**alıyor**, controller ve `DatabaseFactory` ise hâlâ ikisini de **almıyor**.)*

---

## İş 4 / Dilim 3'te uygulanan hâli

```text
domain/backup/automatic/AutomaticSnapshot.kt      AutomaticSnapshot (internal ctor),
                                                  üç SnapshotProblem, SnapshotNotTaken,
                                                  AutomaticSnapshotTaker + …Writer
domain/backup/automatic/VerifiedSnapshotTaker.kt  oku → yaz → GERİ OKU → karşılaştır
desktopMain/platform/backupfiles/ClaimedNameWriter.kt       ad sahiplenme kalıbı,
                                                  iki otomatik yazıcının ORTAK'ı
desktopMain/platform/backupfiles/DesktopImportSnapshotWriter.kt  pnp-otomatik-import-*
data/repository/ImportConfirmationStore.kt        snapshot + housekeeping + yarış kapısı
domain/importconfirm/ImportConfirmationFailure.kt dört yeni tipli ret
ui/…/ConfirmationMessages.kt, Strings.kt, ImportReviewScreen.kt  beş yeni Türkçe metin
```

`DesktopSafetyBackupWriter` artık kendi ad sahiplenme kodunu taşımıyor,
`ClaimedNameWriter`'ı çağırıyor. Ürettiği ad ve davranış birebir aynı; kuralın
iki kopyası, onu iki kez yanlış yapma şansı demek olurdu.

### Snapshot bir SÖZ değil, bir KANIT'tır

```text
1  DatabaseBackupExporter 15 tabloyu okur, kanonik belgeyi üretir
2  DesktopImportSnapshotWriter adı Files.createFile ile sahiplenir, atomik yazar
3  dosya GERÇEK UntrustedBackupReader + TemporaryBackupProbe hattından geri okunur
4  geri okunanın dataSha256'sı VE 15 tablosu, yazılanla karşılaştırılır
5  ancak o zaman bir AutomaticSnapshot vardır
```

4. adım okuyucunun tek başına göremediği durumu yakalar: geçerli bir yedek
belgesi olan fakat **bu** yedek olmayan bir dosya — başka yere düşmüş bir yazma
ya da başkasına ait çıkan bir ad. `AutomaticSnapshot`'ın yapıcısı `internal`
olduğu için, hiç dosya yazmamış bir çağıranın onay transaction'ına verecek bir
şeyi yoktur: PLAN 14.4.13'ün "fail closed" hükmü hatırlanacak bir kural değil,
tiplerin bir özelliğidir.

Doğrulamayı geçemeyen dosya **yerinde bırakılır**, silinmez. Canlı
veritabanından yazılmıştır ve pekâlâ sağlam olabilir — doğrulama bu makine
hakkında bir sebeple de düşebilir; kullanıcı verisinin bir kopyasını "geri
okuyamadım" diye atmak yanlış yön olurdu. Hiçbir yerde ona "yedek" denmez.

### Yarış koruması — somut mekanizma

```text
nerede      ImportConfirmationStore.confirm, useWriterConnection +
            immediateTransaction (LiveBackupRestorer'ın kalıbı, ikinci bir
            tasarım değil)
ne          transaction'ın İLK işi 15 tabloyu yeniden okumak ve snapshot'ın
            BackupData'sıyla TAM karşılaştırmaktır
niçin TAM   sayım, yanlış satırların doğru sayısına "evet" derdi; verilen söz
            verinin yedeğin ANLATTIĞI veri olmasıdır
farklıysa   ChangedUnderneath → hiçbir DELETE/INSERT/UPDATE yapılmadan
            DATA_CHANGED_MEANWHILE ile reddedilir, batch DRAFT kalır
kilit       BEGIN IMMEDIATE yazma kilidini İLK OKUMADAN ÖNCE alır; Room tek
            writer bağlantısı tuttuğu için o andan commit'e kadar bu uygulamada
            başka hiçbir yazma araya giremez
maliyet     iki tam okuma (biri yedek, biri kapı) — taslak sayısından BAĞIMSIZ;
            bir sorgu sayımı testi bunu 1 ve 42 taslakla sabitliyor
```

**Global mutation barrier yine seçilmedi ve gerek de olmadı:** kapı, restore'da
kanıtlanmış olan güvencenin aynısını veriyor. Güvence sessizce kaldırılamaz —
kaldırılırsa `ImportSnapshotBeforeConfirmationTest`'in yarış testi düşer.

### Sıra ve hata sınırları

```text
housekeeping  transaction'dan ÖNCE çalışır (PLAN'ın verdiği sıra). Böylece
              sonradan düşen bir onay bile saklama sayısını uygulamış olur ve
              art arda reddedilen onaylar dosya biriktirmez
              — restore'da tersi seçilmişti; orada güvenlik yedeğiyle transaction
              ARASI, her yazmanın restore'u reddettirdiği dar bir penceredir
snapshot hatası  onay HİÇ başlamaz; batch DRAFT; görev/segment/hücre/tamamlanma/
              geçmiş YAZILMAZ; dört tipli retten biri gösterilir
rotation hatası  onayı ETKİLEMEZ (fail open); housekeeping sözleşmesi gereği
              bir sonuç için atmaz
sonradan düşen onay  snapshot KORUNUR (yazılmış ve doğrulanmış bir yedeği silmek,
              onu hiç almamaktan beterdir)
çift gönderim   controller'ın isBusy'si ikinci basışı store'a hiç sokmaz →
              TEK snapshot, TEK confirmation
history        otomatik snapshot geçmişe satır YAZMAZ; onayın kendi satırları yazılır
```

### Kullanıcıya söylenen

Onay penceresinde tek cümle: *"Onaylamadan önce verilerinizin otomatik bir
yedeği alınır; bu yedeği daha sonra Ayarlar ekranından geri yükleyebilirsiniz."*
Dosya adı, klasör, biçim veya teknik terim yok.

Dört yeni ret cümlesi ayrı ayrı yazıldı; hepsi aynı iki şeyi söyler (hiçbir şey
yazılmadı, içe aktarma taslak olarak duruyor) ve sonra farklı olanı: tekrar
dene, diskte yer aç, veya verileriniz arada değişti.

### Dilim 3'ün bilerek YAPMADIKLARI

```text
migration açılış kapısı / migration snapshot seti   Dilim 4
ham .db klonu, VACUUM INTO ölçümü                   Dilim 4
Ayarlar'da son snapshot zamanı / klasörü açma       kapsam dışı (PLAN 12.16)
taslak oluştururken snapshot                        PLAN 14.4.8 bunu açıkça reddeder
```

`DatabaseFactory` ne `AutomaticSnapshotTaker` ne `AutomaticBackupHousekeeping`
alıyor — ve Dilim 4 geldiğinde de almadı. `RetentionAfterRestoreTest` bunu JVM
refleksiyonuyla iddia etmeye devam ediyor; test her dilimde bilinçli olarak
genişletildi ve son hâlinde üç sahibi (restore, import, kapı) sayıyor. Fabrikanın
boş kalması artık taşıyıcı: kapı, migration çalışma kopyasını o fabrikadan
geçirir.

---

## İş 4 / Dilim 4'te uygulanan hâli

```text
domain/backup/automatic/MigrationSnapshotSet.kt   set tipi (internal ctor),
                                                  sekiz StartupProblem, StartupRefused
desktopMain/platform/startup/ConsistentDatabaseClone.kt   salt okunur VACUUM INTO,
                                                  Room'suz user_version, satır
                                                  sayıları, integrity + FK
desktopMain/platform/startup/InstanceLock.kt      FileChannel.tryLock
desktopMain/platform/startup/MigrationSnapshotSetWriter.kt  iki eşin üretimi ve kanıtı
desktopMain/platform/startup/StartupGate.kt       PLAN 14.4.10'un 17 adımı
commonMain/ui/feature/startup/StartupErrorScreen.kt  sekiz Türkçe cümle
platform/backupfiles/ClaimedNameWriter.kt         + claimSetNames (iki ad, tek sonek)
Main.kt                                           artık DatabaseFactory'yi DOĞRUDAN
                                                  çağırmıyor; yalnız kapıyı çağırır
```

### Tutarlı klon — ölçülmüş mekanizma

Ayrıntılı ölçüm §33 R11'dedir. Özeti: **salt okunur bağlantı + `VACUUM INTO`**.
WAL'da bekleyen satırlar klona geçiyor, `user_version` korunuyor, migration kodu
hiç çalışmıyor, ve kaynağın `.db` ile `-wal` dosyaları **bayt bayt** değişmiyor.
Okuma-yazma açmak kaynağı değiştiriyor (WAL checkpoint edilip siliniyor), bu
yüzden salt okunurluk bir tercih değil zorunluluk. `cp` ile üçlü kopyalama
hiçbir yerde mekanizma olarak kullanılmıyor; testlerde yalnız **kaza fixture'ı
kurmak** için var ve orada tutarlılığı testin kendisi sağlıyor.

### Setin sahiplenilmesi ve kanıtı

```text
ad        claimSetNames iki adı AYNI ANDA Files.createFile ile alır; ikincisi
          doluysa ilki geri verilir ve sonraki sonek denenir → iki eş DAİMA
          aynı soneki taşır, yoksa eşleştirilemez iki yetim kalırdı
ham yazım VACUUM INTO → <set>.db.part → atomik move (VACUUM INTO var olan
          hedefi reddeder, claim ise gerçek boş bir dosyadır)
ham kanıt SQLite başlığı + user_version == addaki v<eski> + integrity_check +
          foreign_key_check + KAYNAKLA AYNI tablolar ve AYNI satır sayıları
          (checksum DEĞİL: VACUUM dosyayı yeniden yazar, bayt eşitliği yanlış
           soru olurdu)
json      ham klonun AYRI çalışma kopyası geçici dizinde gerçek zincirle v8'e
          yürütülür; belge o kopyadan üretilir
json kanıt GERÇEK UntrustedBackupReader + TemporaryBackupProbe ile geri okunur,
          sonra çalışma kopyasının TAZE okumasıyla karşılaştırılır
set       MigrationSnapshotSet'in yapıcısı internal; ikisi de kanıtlanmadan
          örneği YOKTUR, dolayısıyla gerçek DB açılamaz
```

### Yarım kalanla ne yapılır

Üç durum, ve ayrım dosyanın **ne olduğu** üzerinedir:

```text
boş claim              SİLİNİR — ad rezervasyonundan başka bir şey değil
kanıtı DÜŞEN ham yarım SİLİNİR — adının söylediği veritabanı OLMADIĞI kanıtlandı;
                       adı yalan söyleyen bir dosya bırakmak, hiçbir şey
                       bırakmamaktan kötüdür
kanıtı GEÇEN ham yarım KALIR — saniyeler önce alınmış tutarlı bir kopyadır;
                       eşsiz yarım rotation'ın erişiminin dışındadır (Dilim 1),
                       disk harcar, veri kaybettirmez
çalışma dizini + .part  HER YOLDA silinir (PLAN 14.4.10 adım 17)
```

### Instance kilidi

```text
ne          <dataDirectory>/pnp-baslangic.lock üzerinde FileChannel.tryLock
neden OS    stale sorusu ortadan kalkar: çekirdek, process nasıl ölürse ölsün
            kilidi bırakır. PID dosyası olsaydı "o process yaşıyor mu, id
            yeniden mi kullanıldı, ne kadar bekleyelim" sorularının her cevabı
            tahmin olurdu
silinmez    dosya asla silinmez; iki kopya aynı yola descriptor tutarken biri
            unlink ederse ikisi FARKLI dosyaları kilitlemiş olurdu
alınamazsa  beklenmez: PLAN 14.4.10'a göre ikinci kopya veritabanını AÇMAZ,
            ANOTHER_COPY_IS_RUNNING ekranını gösterir ve durur
kapsam      1. adımdan gerçek migration'ın sonuna kadar; başarıda da hatada da
            bırakılır
kanıt       StartupGateTest gerçek bir İKİNCİ PROCESS başlatır (LockHolder),
            kapının reddettiğini gösterir, sonra process'i ÖLDÜRÜR ve kilidin
            kendiliğinden serbest kaldığını gösterir
```

### Set oluşturulmayan durumlar

```text
veritabanı yok / boş dosya      → normal oluşturma, seed, snapshot YOK
zaten v8                        → snapshot YOK
başarılı migration sonrası açılış → dosya artık v8 olduğu için snapshot YOK
                                  (ayrı bir "daha önce yapıldı" defteri YOK;
                                   sürümün kendisi kayıttır)
```

### Migration'ın bilerek reddettiği veritabanı

Ölçüm sırasında üretim kodunda gerçek bir boşluk bulundu ve kapatıldı.
`Migration3To4`, v3'te oyun/öge/görev/görev-rengi taşıyan bir veritabanını
**bilerek** reddeder (PLAN 18: uydurmak yerine dur) ve attığı
`UnconvertibleLegacyDataException` bir `SQLiteException` değildir. Yakalanmadığı
sürece ham exception olarak dışarı çıkıyordu. Artık hem çalışma kopyasında
(`SNAPSHOT_NOT_MIGRATED`) hem gerçek açılışta (`MIGRATION_FAILED`) yakalanıyor:
kullanıcı Türkçe bir ekran görüyor ve **asıl veritabanına hiç dokunulmuyor**.

### Hata ekranı

Sekiz sebep, sekiz ayrı Türkçe cümle, `else` yok. Yedisi "Verileriniz olduğu gibi
duruyor" der; sekizincisi — gerçek migration'ın düştüğü durum — demez, çünkü
orada gerçekten bir şey denenmiştir; onun yerine alınmış yedeğin yedek
klasöründe durduğunu **kelimelerle** söyler. Yol, SQL, UUID, enum, exception
metni, `pnp.db`, `.json` hiçbirinde geçmez; bir test hepsini tarar.

### Dilim 4'ün bilerek YAPMADIKLARI

```text
ham .db'yi uygulama içinden geri yükleme yolu   PLAN 14.4.9 bu işte OLUŞTURULMAZ
                                                (seçici .json süzer, ham dosya
                                                 orada görünmez)
gerçek kullanıcı DB'sinde ilk migration'ı tetiklemek  kullanıcının kararı
R12 (geriye giden saat)                         AÇIK risk olarak korundu
```

---

## İş 4'ün kullanacağı, İş 3'ün bıraktığı yüzey

```text
DatabaseBackupExporter + BackupStore   15 tablo, TEK transaction, kanonik belge,
                                       sourceSchemaVersion'ı PRAGMA'dan okur
AtomicFileWriter                       aynı dizinde .part → atomik move
DesktopSafetyBackupWriter              Files.createFile ile ad sahiplenme kalıbı
safetyBackupFileName(moment, attempt)  locale-bağımsız ad üretimi
UntrustedBackupReader                  25 tipli ret nedeni
TemporaryBackupProbe                   geçici dizinde DB kurma/silme kalıbı
XdgAppPaths.backupsDirectory / settingsFile
LiveBackupRestorer + SafetySnapshot    yarış modelinin çalışan örneği
```

*(Tarihsel not: bu liste İş 3 biterken yazıldı. `settings.json` o gün yalnız bir
yoldu; Dilim 2 onu gerçek bir dosya hâline getirdi ve yokluğunu iddia eden dört
testi silmek yerine güçlendirdi. Dilim 3, `DesktopImportSnapshotWriter`'ı
`DesktopSafetyBackupWriter`'ın yanına koydu ve ikisinin ad sahiplenme kalıbını
`ClaimedNameWriter`'da birleştirdi.)*

---

# 25.3 BEKLENMEYEN KAPANIŞ VE YARIM KALMIŞ İÇE AKTARMA  *(Faz 3 / İş 7 — TAMAMLANDI, 4/4)*

Bağlayıcı metin PLAN `11.4.5`, `16.`, `17.` ve `18.` Faz 3 / İş 7'dedir. Bu
bölüm kararların özetini, **repo denetiminin sonucunu** ve dört dilimin
ayrıntısını tutar. İş 7'nin sözleşmesi `docs: define interrupted import
recovery semantics` commit'iyle yazıldı; o commit **yalnız belge** değiştirdi.

## Verilmiş kararlar  *(yeniden tartışılmaz)*

```text
 1  "temiz kapandı" işaret dosyası YOK; -wal / -shm çökme kanıtı DEĞİL;
    güvence = tek transaction + SQLite WAL kurtarması; ayrı kurtarma ekranı YOK
 2  kaydedilmiş DRAFT kalıcıdır; "Devam eden içe aktarmalar"dan yeniden açılır
 3  kullanıcı geçerli taslağa DEVAM eder veya açık onayla KALDIRIR; hiçbir DRAFT
    otomatik onaylanmaz / silinmez / değiştirilmez / onarılmaz
 4  kaldırma: TEK transaction, tekrar çağrı güvenli, yalnız o batch'in satırları;
    CONFIRMED/ROLLED_BACK batch, görev, hücre, segment, progress, history DOKUNULMAZ
 5  kaynak XLSX/CSV'nin taşınması/silinmesi/değişmesi DRAFT'ı bozuk yapmaz;
    kurtarma kaynak dosyayı YENİDEN OKUMAZ, ham bloklar esastır
 6  "bozuk import" = kalıcı import kayıtlarının OBJEKTİF invariant ihlali;
    tahmine dayalı hiçbir şey bozuk sayılmaz
 7  geçerli taslaklar İçe Aktarma ekranında; bozuk olanlar AYNI ekranda AYRI,
    Türkçe ve eyleme dönük uyarıyla; açılış ENGELLENMEZ
 8  bozuk taslak da otomatik silinmez; yalnız açık onayla kaldırılır
 9  ham migration .db için uygulama içi restore YOK; restore biçimi JSON kalır
10  genel integrity_check hatası ≠ bozuk import; otomatik düzeltme / takas /
    silme YOK → açık risk R13
11  R12 (geriye giden saat) İş 7'de ÇÖZÜLMEZ → İş 10
12  İş 4 / Dilim 4'ün kilidi, sıcak WAL okuması, migration kapısı ve açılış hata
    ekranı YENİDEN UYGULANMAZ; yalnız kullanılır
```

## Repo denetimi: gerçekten oluşabilen bir bozuk DRAFT var mı?

**Kısa cevap:**

- **Uygulamanın kendi yazma yolları ve beklenmeyen kapanış altında: HAYIR.**
- **Doğrulanmış JSON geri yüklemesi yoluyla: EVET, dokuz kesin predicate'le
  sınırlı olarak** — yedek okuyucusu import yaşam döngüsünün satırlar arası
  kurallarını denetlemediği için. Bu ikinci cevap belge turunda kod okumasıydı;
  **Dilim 3 onu gerçek hatla ölçtü: dokuzun dokuzu da canlı DB'ye ulaşıyor**,
  listeden çıkarılacak predicate yok (ölçüm matrisi "İş 7 / Dilim 3'te uygulanan
  hâli").

### Neden kendi yollar bozuk DRAFT üretemez  *(kanıt)*

```text
batch + ham bloklar  ImportDao.saveDraftBatch — @Transaction; status == DRAFT,
                     rawBlockCount == blocks.size ve blokların batch'e aitliği
                     require ile denetlenir. Üretimdeki TEK çağıranı
                     ImportDraftStore.save'dir; createdTaskCount ve
                     createdGameCount orada sabit 0 yazılır
ham blok metni       raw_text / koordinatlar için UPDATE yolu YOK; yalnız
                     is_processed ve oyun tamamlanma kararı güncellenir
inceleme yazımları   setRawBlockProcessed, create*UnderReview, editDraftUnderReview,
                     setDraftColorsUnderReview, setDraftCompletionDecisionUnderReview,
                     setGameCompletionDecisionUnderReview — hepsi @Transaction ve
                     batch'in DRAFT olduğunu AYNI transaction'da yeniden okur
seçim                createDraftFromSelectionUnderReview seçimi veritabanındaki
                     metne karşı keser (selectTaskNameIn); metin değişmez
yeşil hücre kararı   PreparedImportDraft: GAME dışı sütunda hint == NONE (require);
                     setGameCompletionDecisionUnderReview GAME dışını reddeder
sayaç / materialize  created_task_count, draft_tasks.materialized_task_id,
/ batch cells /      import_batch_cells ve tasks.source_raw_import_block_id'yi
provenance           YALNIZ confirmDraftBatch yazar ve aynı transaction batch'i
                     CONFIRMED yapar; requireConfirmationHeld hepsini geri okur
games.source_...     üretimde tek yazan GameSetupStore → her zaman null (PLAN 11.4.1)
kaldırma             removeDraftBatch (Dilim 2; önceden discardDraftBatch) —
                     @Transaction (BEGIN IMMEDIATE), status denetimi SQL'de
                     tekrarlanır, çocuklar CASCADE, provenance RESTRICT
yabancı anahtarlar   üretim bağlantısında ZORLANIR (§33 R9, ölçüldü)
çöküş                bunların hiçbiri transaction dışında çok adımlı değildir;
                     yarıda kesilen transaction hiç uygulanmamış olur
migration            import tabloları v3'te BOŞ yaratıldı (Migration2To3);
                     Migration3To4 taslakların target'ını ve materialized_task_id'sini
                     temizler (belgelenmiş geçerli durum); bilinen bir üretici yok
```

### Geri yükleme kapısındaki boşluk  *(kanıt)*

`BackupValues` satır değerlerini (UUID, enum, negatif sayı, seçim `start < end`,
`isMissing && isBorrowed`, havuz/mod uyumu, hedef oyun ↔ ACCEPTED),
`BackupGraph` başvuruları, benzersiz anahtarları ve sıra boşluklarını,
`TemporaryBackupProbe` / `LiveBackupRestorer` `foreign_key_check`'i denetler.
**Hiçbiri** bir batch'in durumunu çocuk satırlarıyla karşılaştırmaz. Uygulamanın
üretmediği ama `dataSha256`'sı yeniden hesaplanmış bir belge şu durumları canlı
DB'ye taşıyabilir:

| Kod | Predicate (`b` = `status = 'DRAFT'` bir batch) | Üretim yolu neden yazamaz | Dilim 3'ten ÖNCE onaya ulaşırsa beklenen *(kod okuması; artık kapıyla kapalı)* |
|---|---|---|---|
| D1 | `b.created_task_count <> 0` | `ImportDraftStore` 0 yazar; yalnız onay değiştirir | onay sayacı üzerine yazar — iz kaybolur |
| D2 | `b.created_game_count <> 0` | hiçbir yol sıfır dışı yazmaz | `plannedConfirmationOf` `require` → **ham `IllegalArgumentException`** |
| D3 | `b.raw_block_count <>` b'nin blok sayısı | `saveDraftBatch` eşitliği `require` eder; blok ekleme/silme yolu yok | liste yanlış hücre sayısı gösterir |
| D4 | b'nin bir taslağında `materialized_task_id IS NOT NULL` | yalnız onay yazar | onay taslağın eski görev bağının üzerine yazabilir — eski görev iz kaybeder |
| D5 | `import_batch_cells`'te `import_batch_id = b.id` | yalnız onay yazar | `import_batch_cells` PK çakışması → depolama hatası gibi görünen yanıltıcı sebep |
| D6 | `tasks.source_raw_import_block_id` b'nin bir bloğu | yalnız onay yazar | kaldırma FK RESTRICT ile düşer |
| D7 | `games.source_import_batch_id = b.id` | üretimde her zaman null | kaldırma FK RESTRICT ile düşer |
| D8 | b'nin bir taslağında `selection_end_index >` blok metninin UTF-16 uzunluğu | seçim yazılırken metne karşı denetlenir, metin değişmez | `SELECTION_NO_LONGER_FITS`; taslak onaylanamaz, kullanıcı düzeltemez |
| D9 | b'nin bir bloğunda `source_column_type <> 'GAME' AND game_completion_hint <> 'NONE'` | hazırlık `require` eder, inceleme reddeder | kabul edilmiş ve hedef oyunu varsa onay o oyunu bitirir (`acceptedCompletionTargetsOfBatch` sütun süzmez) |

Bu tablo D2, D4, D5 ve D9'un neden yalnız bir uyarı değil **onay kapısı**
gerektirdiğini de gösterir: bozuk bir taslak onaya ulaşırsa ya ham exception
çıkar, ya yanlış sebep gösterilir, ya da başka bir kayıt sessizce değişir. Son
sütun Dilim 3 öncesinin kod okumasıdır ve **tarihseldir**: Dilim 3'ün ölçümü
dokuz satırın dokuzunun da gerçek restore hattından canlı DB'ye ulaştığını
gösterdi, ve onay artık bu taslakların hiçbirinde domain yazımına ulaşmaz — ilk
kapı snapshot'tan önce, ikinci kapı transaction içinde reddeder.

### Bozuk SAYILMAYANLAR  *(hayalî dedektör yazılmaz)*

```text
hedef hücresi boş / silinmiş oyunda / yanlış sütunda  → onayın tipli reddi; kullanıcı düzeltir
seçim metin içinde ama grapheme ortasında            → Unicode tablosuna bağlı; SELECTION_NO_LONGER_FITS
** kararının metinle uyumu                           → ayrıştırıcıyı yeniden çalıştırmak gerekir
Migration3To4'ün temizlediği target / materialized   → belgelenmiş geçerli durum
v5'ten ACCEPTED ama oyunu seçilmemiş yeşil hücre     → COMPLETION_TARGET_GAME_REQUIRED; kullanıcı seçer
updated_at < created_at                              → geriye giden saat; üretimde de oluşur; R12 → İş 10
kaynak dosyanın kaybolması / değişmesi               → karar 5
hiç taslağı olmayan / hiç işlenmemiş DRAFT            → olağan
CONFIRMED / ROLLED_BACK batch'lerin tutarsızlıkları  → İş 7'nin konusu DEĞİL (R14)
```

### Beklenmeyen kapanışın dosya sistemi izleri  *(denetim, karar değil)*

```text
DB transaction'ı içinde öldürme   yarım satır YOK; bir sonraki açılışta WAL kurtarması
onay: snapshot yazıldı, commit    snapshot dosyası geçerli kalır; batch DRAFT; yeniden
      öncesi öldürme              denemek YENİ bir snapshot adı alır
ClaimedNameWriter: ad alındı,     0 baytlık pnp-otomatik-*.json (ve belki .json.part)
      move öncesi öldürme         kalır; hata yolundaki temizlik öldürmede çalışmaz.
                                  Rotation sahiplenmez/saymaz/silmez (PLAN 14.4.11 —
                                  ilk baytlar gerekir); okuyucu EMPTY_FILE ile reddeder
migration seti yazılırken         DB v3'te kalır; boş claim / .db.part / eksik eşli set
      öldürme                     kalabilir, rotation dokunmaz; sonraki açılış yeni
                                  sonekle yeni set yazar; çalışma dizini sistem
                                  temp'indedir (kullanıcı veri dizini DEĞİL)
gerçek migration sırasında        Room zinciri tek transaction'da yürütür → DB v3'te
      öldürme                     kalır, set korunur (İş 4 kapsamı)
açılış kilidi                     FileChannel.tryLock — süreç ölünce OS bırakır
```

Bu izler için **temizlik eklenmez** (PLAN `11.4.5` dışında kalanlar): kullanıcı
verisi kopyaları gelişigüzel silinmez ve boş dosyalar hiçbir sayıma girmez.

### Kalıcılık ayarı  *(ÖLÇÜLDÜ — Dilim 1)*

`DatabaseFactory` `journal_mode` veya `synchronous` belirtmez; Room'un
varsayılanı geçerlidir. `room3-runtime-jvm-3.0.1` içindeki
`BaseRoomConnectionManager` sabitleri iki çift içerir: `journal_mode = WAL` ile
`synchronous = NORMAL`, ve `journal_mode = TRUNCATE` ile `synchronous = FULL`.
Hangisinin geçerli olduğu **ölçüldü**:

```text
yeni DB, writer bağlantısı      journal_mode = wal   synchronous = 1 (NORMAL)
yeni DB, reader bağlantısı      journal_mode = wal   synchronous = 1 (NORMAL)
dosya başlığı bayt 18 / 19      2 / 2   → WAL dosyanın KENDİSİNE yazılı (kalıcı)
temiz kapanıştan sonra          -wal YOK (checkpoint edilip silindi)
yeniden açılıştan sonra         wal / 1 / wal / 1   (aynı)
SIGKILL sonrası yeniden açılış  wal / 1 / wal / 1   (aynı)
test sürücüsü sargısıyla child  wal / 1 / wal / 1   (sargı modu DEĞİŞTİRMİYOR)
```

Sonuç beklenen SQLite/Room dayanıklılık modeliyle **uyuşuyor** ve üretim
değişikliği gerektirmedi. Anlamı: WAL + NORMAL'da süreç öldürülmesi commit
edilmiş hiçbir şeyi kaybettirmez (Dilim 1 bunu ölçtü); işletim sistemi çökmesi
veya güç kesintisi **en son** commit'leri geri alabilir (bu makinede test
edilemez, ölçülmedi), **yarım transaction bırakamaz**. İş 7 ayarı değiştirmez.

## Bugünkü eksik  *(İş 7'nin gerçek iş yükü)*

```text
kaldırma motoru     Dilim 2'de YAZILDI; Dilim 4'te UnfinishedImportsStore üzerinden
                    Main'e BAĞLANDI → kullanıcı taslağı açık onayla kaldırabilir
tekrar çağrı        Dilim 2'den beri tipli ALREADY_REMOVED (önce IllegalArgumentException)
FK engeli           Dilim 2'den beri açık SELECT → HELD_BY_RECORDS (önce SQLiteException)
postcondition       Dilim 2'de EKLENDİ — 15 tablonun sayımı önce/sonra
bozuk sınıflandırma Dilim 3'te YAZILDI: ImportDao.draftHealthOf (salt okunur,
                    üç okuma); onay iki kapıyla kapalı → RECORDS_CONTRADICT_EACH_OTHER.
                    Dilim 4'te listede AYRI bölüm + Türkçe uyarı + yalnız Kaldır
kesinti kanıtı      Dilim 1'de EKLENDİ: dört yazma yolu gerçek bir ikinci
                    süreçte transaction İÇİNDE ve commit'ten SONRA öldürülüyor
```

## Dört atomik dilim

### Dilim 1 — kesintiye dayanıklılığın kalıcı kanıtı

```text
kapsam         yalnız test. Dilim 4'ün LockHolder kalıbıyla gerçek ikinci JVM
               süreci, geçici XDG ve DatabaseFactory'nin mevcut `driver`
               seam'i üzerinden transaction içinde bekletilir ve ÖLDÜRÜLÜR:
                 a  saveDraftBatch blok eklerken
                 b  editDraftUnderReview renkleri silip eklerken
                 c  onay: snapshot doğrulandıktan sonra, immediate
                    transaction içinde
                 d  rollBackConfirmedBatch içinde
                 e  commit'ten hemen SONRA
               her birinde yeniden açılış StartupGate'ten geçer (kilit OS
               tarafından bırakılmış, v8 → set yok) ve şunlar doğrulanır:
               hepsi ya da hiçbiri, foreign_key_check + integrity_check temiz,
               DRAFT listesi, snapshot dosyasının geçerliliği, onayın yeniden
               denenebilmesi. Ayrıca: normal kapanış ve öldürme sonrasında veri
               ve ayar dizinlerinde işaret dosyası yok; boş claim + .part
               rotation'da sayılmıyor/silinmiyor ve okuyucu EMPTY_FILE diyor;
               PRAGMA journal_mode ve synchronous ölçülüp §33'e yazılıyor.
kullanıcıya    HİÇBİR ŞEY
yapılmayacak   üretim kodu değişmez; ölçüm bir kusur gösterirse düzeltmeye
               geçmeden durulur ve raporlanır. synchronous değiştirilmez
kabul          PLAN Faz 3 testlerinin ilk beş kesinti maddesi; süreç sonrası
               arkada JVM kalmaz; gerçek DB/settings/backups dokunulmaz
Room           migration YOK, şema 8
commit         test(recovery): prove an interrupted write leaves all or nothing
neden ilk      karar 1 "SQLite yeter" diyor; üstüne bir kullanıcı akışı
               kurulmadan önce o cümle kalıcı bir ölçümle sabitlenmelidir
```

### Dilim 2 — taslağı kaldırma motoru

```text
kapsam         ImportDao'da tek transaction'lı kaldırma: batch'i okur;
               yoksa → ALREADY_REMOVED (yazma yok); DRAFT değilse → NOT_A_DRAFT;
               D6/D7 başvurusu açık bir SELECT ile bulunursa → HELD_BY_RECORDS
               (FK hata mesajına güvenilmez); aksi hâlde silinecek satırları
               sayar, diğer 11 tablonun sayımını alır, siler, postcondition ile
               yalnız sayılanların gittiğini ve diğerlerinin aynı kaldığını
               doğrular. Store: tipli sonuç; SQLiteException → COULD_NOT_SAVE;
               programlama hataları olduğu gibi geçer.
               discardDraftBatch'in mevcut testleri SESSİZCE SİLİNMEZ: API
               değişirse her biri bilinçli olarak yeni sonuçlara dönüştürülür
kullanıcıya    HİÇBİR ŞEY (arayüz yok)
yapılmayacak   geçmiş olayı, otomatik snapshot, kaynak dosya okuma, CONFIRMED /
               ROLLED_BACK için kaldırma, kısmi kaldırma
kabul          PLAN Faz 3 testlerinin kaldırma maddeleri; sorgu sayısı blok ve
               taslak sayısından bağımsız (1 blok = 42 blok); kaynak dosya
               silinmişken kaldırma; transaction yarıda kalırsa 0 değişiklik;
               kaldırma ile eşzamanlı onayın mevcut DATA_CHANGED_MEANWHILE /
               BATCH_NOT_FOUND koruması altında kalması
Room           migration YOK (CASCADE'ler v3/v5/v8'den beri şemada)
commit         feat(import): remove an unconfirmed import in one transaction
neden ikinci   arayüzden önce motorun reddedişleri tipli ve postcondition'ı
               kanıtlı olmalı; Dilim 3'ün bozuk taslak için sunacağı tek eylem
               bu motordur
```

### Dilim 3 — bozuk DRAFT sınıflandırması ve onay kapısı

```text
kapsam         önce ÖLÇÜM: D1–D9'un her biri, elle kurulmuş bir belgeyle GERÇEK
               UntrustedBackupReader + TemporaryBackupProbe + LiveBackupRestorer
               hattından canlı geçici DB'ye taşınır. Ulaşamayan predicate
               listeden çıkarılır ve PLAN 11.4.5 bir belge düzeltmesiyle
               güncellenir — tahmin edilmez.
               Sonra salt okunur sınıflandırma: bütün DRAFT batch'ler için sabit
               sayıda sorgu; D8 Kotlin'de UTF-16 uzunluğuyla (SQLite length()
               kod noktası saydığı için SQL'e bırakılmaz).
               Onay: ImportConfirmationStore snapshot'tan ÖNCE denetler; aynı
               denetim onay transaction'ının içinde, 15 tablo karşılaştırmasından
               sonra yeniden yapılır; bozuksa yeni tipli ImportConfirmationFailure
               ile hiçbir şey yazmadan reddedilir. Tek yeni Türkçe cümle, yalnız
               "onaylanamaz ve hiçbir şey değişmedi" der — henüz var olmayan
               kaldırma eylemini VAAT ETMEZ
kullanıcıya    yalnız bozuk bir taslağı onaylamaya çalışan kullanıcı yeni cümleyi
               görür (bugün ya ham exception ya yanlış sebep ya sessiz değişiklik)
yapılmayacak   yedek okuyucusunu sıkılaştırma (R14); CONFIRMED/ROLLED_BACK
               sınıflandırma; açılışta denetim; otomatik silme/düzeltme;
               inceleme yazımlarına yeni denetim
kabul          her predicate tek başına bozuk; "bozuk sayılmayanlar" listesinin
               her maddesi sağlıklı; denetim 0 yazma ve sabit sorgu; bozuk taslak
               için snapshot dosyası OLUŞMAZ ve 15 tablo aynı kalır; mevcut
               onay, snapshot, yarış ve sorgu sayısı sözleşmeleri korunur;
               IllegalStateException maskelenmez
Room           migration YOK (predicate'ler mevcut sütunlardan)
commit         feat(import): recognise an unconfirmed import whose records contradict each other
neden üçüncü   arayüz neyi ayrı göstereceğini ancak ölçülmüş bir sınıflandırmadan
               öğrenebilir; onay kapısı ise arayüzden bağımsız olarak bugünkü
               sessiz değişiklik yollarını (D4, D9) kapatır
```

### Dilim 4 — arayüz: devam et, kaldır, bozuk taslak uyarısı

```text
kapsam         "Devam eden içe aktarmalar": geçerli satırda mevcut Aç + yeni
               Kaldır; bozuk taslaklar ayrı ve adlandırılmış bölümde, eyleme
               dönük Türkçe uyarı ve YALNIZ Kaldır (açma/onay yok).
               Onay penceresi PLAN 11.4.5'in dört cümlesini söyler; odak
               Vazgeç'te; yazma sırasında Escape/dışarı tıklama etkisiz; tek
               yüzey ve çift gönderim koruması (ImportRollbackController kalıbı);
               sonuçlar: başarı, zaten kaldırılmış (sessiz başarı gibi
               GÖSTERİLMEZ ama hata da değildir), taslak değil, gerçek kayıtlar
               başvuruyor, kaydedilemedi. Odak işlemi başlatan satıra döner;
               satır kaldırılınca listede bir sonrakine
kullanıcıya    taslağa devam etme ve açık onayla kaldırma; bozuk taslak uyarısı
yapılmayacak   açılışta bildirim/engelleme; "kurtarma" ekranı; kaynak dosyayı
               yeniden okuma; toplu kaldırma; ham .db geri yükleme; history satırı
kabul          PLAN Faz 3 testlerinin arayüz maddeleri; klavye, dar pencere ve
               2× DPI; sızıntı taraması (tablo adı, D-kodu, UUID, SQL, enum,
               yol, exception); geçici XDG ile uçtan uca smoke: XLSX ve CSV
               taslağı, süreç öldürme, yeniden açılış, devam, kaldırma; gerçek
               DB/settings/backups dokunulmaz
Room           migration YOK
commit         feat(import): let an unfinished import be continued or removed
neden son      motor (2) ve sınıflandırma (3) kanıtlanmadan açılan bir kaldırma
               düğmesi, sonucu tipli olmayan ve bozuk taslağı ayırt etmeyen bir
               yıkıcı eylem olurdu
```

## İş 7 / Dilim 1'de uygulanan hâli

Yalnız test ve ölçüm. `desktopTest/kotlin/dev/pnptracker/platform/recovery/`:

```text
StoppingSqliteDriver.kt   gerçek BundledSQLiteDriver'ı sarar; seçilen ifadenin
                          N'inci çalışmasında, ifade SQLite'a GİTMEDEN önce,
                          transaction'ı tutan bağlantıyı onReached'e verir.
                          onReached geri dönmez. DatabaseFactory'nin mevcut
                          `driver` parametresinden girer — üretimde hook YOK
InterruptedWriter.kt      child process'in main'i. Main gibi kurulur: yollar
                          kendi XDG_DATA_HOME / XDG_CONFIG_HOME'undan, DB
                          StartupGate'ten, yazma gerçek store'dan
RecoverySupport.kt        iki tarafın ortak sözlüğü: gateFor, gerçek onay
                          store'u, 15 tablo parmak izi, durability ölçümü,
                          fixture'lar (ImportDraftStore + ImportReviewStore ile)
RecoveryHome.kt           test başına geçici ev (data/config/tmp), child başlatma,
                          kapıdan yeniden açılış + integrity/FK, dosya listesi,
                          gerçek uygulama dosyalarının açılmadan parmak izi
InterruptedWriteTest.kt   8 test — dört yol × (içeride öldür, commit sonrası öldür)
DurabilityTest.kt         2 test — journal_mode / synchronous / başlık baytları
UnexpectedShutdownTest.kt 4 test — işaret yok, WAL çökme değil, normal kapanış,
                          kesilmiş yedek artıkları
```

### Protokol — zamanlamaya değil olaya dayalı

```text
1  test DB'yi kapıdan açar, fixture'ı gerçek store'larla kurar, kapatır
2  child JVM başlar (-XX:-UsePerfData, java.io.tmpdir = evin tmp'si,
   XDG_DATA_HOME / XDG_CONFIG_HOME = evin data/config'i)
3  child der ki:  RECOVERY:DURABILITY wal/1/wal/1
                  RECOVERY:BEFORE <15 tablonun parmak izi>
4  ya  RECOVERY:INSIDE <yol> true <transaction'ın O ANA KADAR yazdıkları>
       (aynı bağlantıdan okunur) → latch'te bekler
   ya  RECOVERY:COMMITTED <parmak izi> → latch'te bekler
   ya  RECOVERY:CLOSED <parmak izi> → DB'yi kapatır, exit 0
5  test SATIRI ALDIKTAN SONRA destroyForcibly (SIGKILL) + waitFor;
   çıkış kodu 137 ve alt süreç yok doğrulanır
6  yeniden açılış kapıdan: set == null, integrity_check == [ok],
   foreign_key_check == [], durability aynı, parmak izi = BEFORE / COMMITTED
```

`sleep` yok. Satırlar ayrı bir thread'de kuyruğa okunur ve test satır geldiği
anda ilerler; kuyruktaki 3 dakikalık sınır yalnız bozuk bir child'ın testi
sonsuza kadar asmasına karşı bir emniyettir, ölçülen bir şey değildir. Seçilen
ifadeye uğramadan biten bir yazma `MISSED` der ve çıkar; yanlış yerde öldürme
mümkün değildir.

Parmak izi, yedeğin kendi `dataSha256`'sıdır (15 tablonun kanonik JSON'u).
Yani "kısmi yan etki yok" iddiası seçilmiş birkaç tablo hakkında değil,
uygulamanın **bütün satırları** hakkındadır.

### Kesilen dört işlem ve ölçülen sonuçlar

```text
yol / kesme noktası                      transaction'ın o ana kadar yazdığı   yeniden açılış
───────────────────────────────────────  ───────────────────────────────────  ───────────────────────────
taslak kaydı (ImportDraftStore.save)      "1 batch, 2 cells"                   parmak izi = BEFORE;
  5 ham hücreden 3.'sünün INSERT'ü                                             0 batch, 0 hücre
  commit sonrası                                                               parmak izi = COMMITTED;
                                                                               1 DRAFT, 5 hücre,
                                                                               draftBatches()'te görünür
taslak düzenleme (saveDraft)              "Mavi figür, 0 colours"              parmak izi = BEFORE;
  ilk draft_task_colors INSERT'ü          (satır güncellenmiş, eski renk       ad "Kırmızı figür", adet 3,
                                           silinmiş)                           renk [c0 → 0]
  commit sonrası                                                               ad "Mavi figür", adet 5,
                                                                               renk [c1 → 0, c0 → 1]
onay (ImportConfirmationStore.confirm,    "CONFIRMED, 1 task, 1 piece,         parmak izi = BEFORE; DRAFT,
  gerçek snapshot + rotation)              1 cell snapshot, 0 history"         sayaç 0, görev/renk/aşama/
  ilk history_events INSERT'ü                                                  parça/hücre anlık görüntüsü/
                                                                               geçmiş 0, materialized null;
                                                                               snapshot dosyası GERÇEK
                                                                               okuyucudan Valid ve parmak
                                                                               izi = BEFORE; sonraki
                                                                               açılışta onay YENİDEN
                                                                               denenir ve başarılı olur
                                                                               (ikinci snapshot alınır)
  commit sonrası                                                               CONFIRMED, 1 görev, bağlı
                                                                               parça, 1 hücre anlık
                                                                               görüntüsü, 1 IMPORT_CONFIRMED
geri alma (ImportRollbackStore.rollBack)  "CONFIRMED, 1 tombstone, 0 piece,    parmak izi = BEFORE;
  UPDATE … 'ROLLED_BACK'                   3 history"                          CONFIRMED, tombstone yok,
                                                                               parça duruyor, yalnız
                                                                               IMPORT_CONFIRMED
  commit sonrası                                                               ROLLED_BACK, tombstone,
                                                                               parça yok, üç geçmiş satırı
```

"Transaction'ın o ana kadar yazdığı" sütunu kesmenin gerçekten **yarım** bir
işe denk geldiğinin kanıtıdır: onay yolunda batch durumu, görev, parça ve hücre
anlık görüntüsü transaction içinde yazılmış hâldeydi — ve yeniden açılışta
hiçbiri yoktu. Commit sonrası öldürmelerde `-wal` dosyası boş değildi: commit
edilen veri **yalnız log'daydı** ve SQLite'ın WAL kurtarması onu geri getirdi.

### Beklenmeyen kapanışın izleri — ölçülmüş

```text
normal kapanış sonrası   data: backups/, backups/<snapshot>, pnp-baslangic.lock,
                         pnp.db, pnp.db.lck    config: boş
                         (-wal / -shm YOK; tmp BOŞ)
SIGKILL sonrası          yukarıdakiler + pnp.db-wal + pnp.db-shm
                         tmp: tek bir androidx_sqliteJni<sayı>.tmp
bir sonraki açılıştan    normal kapanışla BİREBİR aynı liste; işaret dosyası,
sonra                    kurtarma dosyası, ek yedek, settings.json YOK;
                         ikinci açılış da hiçbir şey değiştirmez
üretim kaynağında        addShutdownHook ve deleteOnExit YOK (tarama testi)
pnp.db.lck               Room'un kendi dosya kilidi — gerçek veri dizinindeki
                         .lck dosyasının açıklaması budur; bizim yazdığımız bir
                         işaret DEĞİLDİR
androidx_sqliteJni*.tmp  bundled sürücünün açtığı native kütüphane kopyası;
                         normal çıkışta silinir, SIGKILL'de kalır. Kullanıcı veri
                         dizininde değil java.io.tmpdir'dedir, veri taşımaz,
                         uygulama onu bir şeyin kanıtı saymaz. İş 7 temizlemez
                         (karar vermek gerekir); gözlem olarak kayıtlıdır
```

### Kesilmiş otomatik yedek artıkları — silinmiyor

Gerçek ad üreticileriyle (`importSnapshotFileName`, `claimSetNames` +
`migrationSnapshotSetName`) bir öldürmenin bırakabileceği beş dosya kuruldu:
0 baytlık import adı, yanında yarım `.json.part`, migration setinin iki boş
yarısı ve `.db.part`. Hepsi gerçek yedeklerden **daha eski** tarihli. Ayar
`automaticBackupCount = 1` yapıldı ve iki gerçek onay (gerçek snapshot + gerçek
rotation) çalıştırıldı:

```text
rotation çalıştı mı      EVET — iki gerçek yedekten yalnız yenisi kaldı
artıklar                 beşi de yerinde, SHA-256'ları bayt bayt aynı
0 baytlık .json          gerçek okuyucu: Refused(EMPTY_FILE)
.part dosyaları          automaticBackupNameOf → null (rotation adı bile tanımıyor)
```

### Gerçek kullanıcı dosyaları

`RecoveryHome.close()` her testte, **dosyaları açmadan**, gerçek XDG yollarındaki
`pnp.db`, `-wal`, `-shm`, `pnp.db.lck`, `pnp-baslangic.lock`, `backups/`,
config dizini ve `settings.json` için var/yok + boyut + mtime (+ dizinde girdi
sayısı) karşılaştırır. Child süreçler gerçek XDG'yi hiç görmez: ortam
değişkenleri ve `java.io.tmpdir` test evine yönlendirilir.

### Dilim 1'in bilerek YAPMADIKLARI

```text
üretim kodu / UI / Room şeması / migration   değişmedi
synchronous ayarı                            değiştirilmedi (ölçüldü)
D1–D9 sınıflandırması                        Dilim 3
taslak kaldırma motoru                       Dilim 2
kullanıcı arayüzü                            Dilim 4
JNI tmp artığının temizliği                  yapılmadı; karar değil gözlem
güç kesintisi / OS çökmesi                   bu makinede test edilemez; ölçülmedi
R12, R13, R14                                AÇIK kaldı
```

## İş 7 / Dilim 2'de uygulanan hâli

Taslağı kaldırma motoru. Arayüz yok; Room şeması, migration zinciri, onay
kapısı ve Dilim 1'in süreç testleri değişmedi.

```text
commonMain/domain/importremoval/DraftRemoval.kt       DraftRemovalOutcome (Removed /
                                                      Refused) + DraftRemovalRefusal (4)
commonMain/data/database/projection/DraftRemovalRows.kt  DraftRemovalFacts (7 sayım,
                                                      tek SELECT) + TableCounts (15 tablo,
                                                      tek SELECT)
commonMain/data/database/dao/ImportDao.kt             discardDraftBatch → removeDraftBatch;
                                                      requireRemovalHeld; iki yeni sorgu
commonMain/data/repository/ImportDraftRemovalStore.kt ImportDraftRemoval arayüzü + store
```

### Tipli sonuçların tam listesi

```text
Removed(batchId, rawBlockCount, draftTaskCount,       taslak ve kendi satırları gitti
        draftColorCount, cellSnapshotCount)
Refused(batchId, ALREADY_REMOVED)   batch yok — ikinci çağrı ile hiç var olmamış id
                                    AYIRT EDİLEMEZ ve ikisi de yapacak iş bırakmaz
Refused(batchId, NOT_A_DRAFT)       CONFIRMED veya ROLLED_BACK (geri almanın yolu 11.4.4)
Refused(batchId, HELD_BY_RECORDS)   D6 (tasks.source_raw_import_block_id, silinmiş
                                    görev dâhil) veya D7 (games.source_import_batch_id,
                                    silinmiş oyun dâhil)
Refused(batchId, COULD_NOT_SAVE)    YALNIZ store üretir: SQLiteException → transaction
                                    geri alındı, hiçbir şey yazılmadı
```

İlk üç ret DAO'dan **değer** olarak döner — reddedildiği noktada hiçbir şey
yazılmamıştır. `IllegalStateException` (postcondition), `IllegalArgumentException`
ve diğer programlama hataları **olduğu gibi yükselir**; store yalnız
`SQLiteException`'ı yakalar. Beklenen çakışma (D6/D7) FK hata metnine hiç
bırakılmaz: aynı transaction'daki açık bir sayımla bulunur.

### Transaction sırası

```text
BEGIN IMMEDIATE                (Room @Transaction; ÖLÇÜLDÜ — yazma kilidi ilk okumadan önce)
1  SELECT * FROM import_batches WHERE id = ?      yoksa → ALREADY_REMOVED
                                                  DRAFT değilse → NOT_A_DRAFT
2  DraftRemovalFacts (tek SELECT, 7 alt sayım)    holding_task/game > 0 → HELD_BY_RECORDS
3  TableCounts (tek SELECT, 15 alt sayım)         "önce"
4  DELETE FROM import_batches WHERE id = ? AND status = 'DRAFT'
   → raw_import_blocks, draft_tasks, draft_task_colors, import_batch_cells
     şemanın ON DELETE CASCADE'iyle AYNI ifadenin içinde gider; check(silinen == 1)
5  DraftRemovalFacts yeniden: batch + 4 çocuk sayımı 0 olmalı
6  TableCounts yeniden: 5 import tablosu tam olarak sayılan kadar eksilmeli,
   diğer 10 tablo AYNI kalmalı → değilse IllegalStateException, commit YOK
COMMIT
```

**Tablo sayısı notu:** PLAN `11.4.5` kaldırmanın dokunabileceği beş tabloyu
(`import_batches`, `raw_import_blocks`, `draft_tasks`, `draft_task_colors`,
`import_batch_cells`) ve dokunamayacağı on tabloyu adıyla sayar. Dilim 2'nin
plan metni "diğer 11 tablo", dilim talimatı "izin verilmeyen 13 tablo" diyordu;
uygulanan postcondition ikisinden de geniştir: **15 tablonun hepsi** sayılır,
izinli beşte yalnız bu batch'in kendi satırları kadar eksilme kabul edilir.
Testler ise sayımla değil **değerle** doğrular: işlem öncesi bütün veritabanının
kanonik okuması, bu batch'in satırları çıkarılmış hâliyle, işlem sonrasına
birebir eşit olmalıdır (başka batch'lerin satırları dâhil).

Postcondition sayıma dayanır, değer karşılaştırmaz: tek bir `DELETE` bir satırın
değerini değiştiremez, yalnız satır götürebilir; götürülen her satır sayıda
görünür. Tam değer karşılaştırması veritabanı boyutuyla büyürdü (§29'daki
değişmez sorgu sayısı sözü). Tetikleyiciyle kurulmuş iki bozulma — izinsiz bir
tablodan satır silen ve batch satırını geri koyan — postcondition'ı düşürüyor
ve veritabanı bayt bayt önceki hâlinde kalıyor (testli; mutasyonla da
doğrulandı: postcondition ve HELD denetimi kapatılınca 4 test düşüyor).

### Ölçülen sorgu sayısı

```text
0 hücreli / 1 hücreli / 42 hücre × 2 taslak × 3 renk   AYNI harita:
  SELECT batch 1 · SELECT facts 2 · SELECT table counts 2 · DELETE 1
  + Room'un SAVEPOINT/RELEASE çifti 1 + changes() okuması 1
ALREADY_REMOVED yolu                                   SELECT batch 1, yazma 0
ilk okumadan önceki son transaction işareti            BEGIN IMMEDIATE TRANSACTION
```

İlk tam koşuda sorgu sayımı testi **bir kez düştü**: Room'un invalidation
tracker'ı commit'ten sonra kendi `BEGIN IMMEDIATE … COMMIT` çiftini kendi
zamanlamasıyla açıyor ve bu çift bazen kayıt penceresine giriyordu. Test
düzeltildi: üst düzey `BEGIN`/`COMMIT` sayımdan çıkarıldı, removal'ın kendi
transaction'ı ise **sıra** ile doğrulanıyor (ilk okumadan önceki son işaret
`BEGIN IMMEDIATE`). Düzeltmeden sonra dar test 5 kez art arda ve tam koşu
geçti. Üretim kodu bu yüzden değişmedi.

### Yarışlar — sleep yok

İkinci işlem, birincinin transaction'ının **içinden**, seçilen ifadede,
`PausingSqliteDriver` ile başlatılır (restore yarış testinin kalıbı). Room tek
writer bağlantısı tuttuğu için ikinci işlem birinci commit edene kadar kendi
transaction'ını açamaz ve kararını birincinin bıraktığından verir.

```text
kaldırma sürerken düzenleme      Removed(3,3,3,0); düzenleme → ImportReviewException
                                 DRAFT_TASK_NOT_FOUND, hiçbir şey yazmaz
düzenleme sürerken kaldırma      düzenleme true; kaldırma sonra Removed(3,3,4,0) — 4
                                 renk, düzenlemenin YENİ iki rengini tam okuduğunun
                                 kanıtı. Bu sırada ikisi de meşru olarak başarılıdır:
                                 önce düzenleme commit edilir, sonra taslak bütünüyle
                                 kaldırılır; hiçbir sırada yarım durum yok
onay sürerken kaldırma           onay 3 görevle CONFIRMED; kaldırma NOT_A_DRAFT;
                                 batch'in blokları ve 3 materialized taslağı yerinde
kaldırma sürerken onay           onay (snapshot kaldırmadan ÖNCE alınmış) →
                                 DATA_CHANGED_MEANWHILE, hiçbir şey yazmaz
kaldırma commit edildikten sonra onay   BATCH_NOT_FOUND, hiçbir şey yazmaz
kaldırma sürerken ikinci kaldırma       ALREADY_REMOVED
8 eşzamanlı kaldırma (Dispatchers.IO)   tam 1 Removed + 7 ALREADY_REMOVED
her yarıştan sonra                      foreign_key_check boş, integrity_check ok,
                                        veritabanı = önce − bu batch (değer düzeyinde)
```

### Failure injection

`FailingSqliteDriver` ile gerçek SQLite hatası altı noktada (batch okuması,
facts okuması, önce-sayım, DELETE, sonra-facts, sonra-sayım) ve bir SQLite
tetikleyicisiyle **cascade'in ortasında** (`RAISE(ABORT)`, taslağın renklerinin
bir kısmı silindikten sonra) enjekte edildi. Her birinde sonuç
`COULD_NOT_SAVE`, bütün veritabanı değer düzeyinde aynı, iki PRAGMA temiz;
hata kaldırılınca aynı kaldırma başarılı. `PausingSqliteDriver`'dan fırlatılan
`IllegalStateException` (DELETE'ten önce) ve `IllegalArgumentException`
(postcondition sayımında) **maskelenmeden** yükseliyor ve veritabanı aynı kalıyor.

### Testler

```text
data/database/DraftRemovalFixtures.kt        aDraftImport (saveDraftBatch + inceleme
                                             yolları), wholeDatabase, withoutDraft,
                                             soundnessProblemsOf, executeRawSql
data/database/DraftRemovalTest.kt        13  0/1/5/42 hücre, renksiz/taslaksız, ikinci
                                             çağrı, bilinmeyen id, gerçek CONFIRMED +
                                             gerçek ROLLED_BACK, D6 (silinmiş görev),
                                             D7 (silinmiş oyun), D4+D5 taşıyan taslak
                                             kaldırılır ve görev/hücre dokunulmaz,
                                             history ve 10 tablo aynı, iki postcondition
                                             bozulması
data/database/DraftRemovalFailureTest.kt  9  yukarıdaki failure injection
data/repository/DraftRemovalRaceTest.kt   7  yukarıdaki yarışlar
data/repository/DraftRemovalQueryCountTest.kt 2  sorgu şekli
platform/recovery/DraftRemovalSmokeTest.kt 2  geçici XDG: gerçek CSV → gerçek gateway +
                                             ImportController + ImportDraftStore;
                                             StartupGate ile kapat/aç; kaynak dosya
                                             SİLİNDİ / YENİDEN YAZILDI; kaldırma →
                                             Removed(6 hücre); backups/ BOŞ (otomatik
                                             snapshot yok), settings.json yok, dosya
                                             listesi aynı; yeniden açılış assertWhole,
                                             history boş ve parmak izi İÇE AKTARMADAN
                                             ÖNCEKİ değere eşit; sonraki açılışta
                                             ikinci çağrı ALREADY_REMOVED
```

Bilinçli olarak dönüştürülen (silinmeyen) eski testler:

```text
ImportDraftTest  removing a draft batch removes only its own cells and drafts
                 → artık Removed(1,1,0,0) sonucunu da doğrular
ImportDraftTest  a confirmed or rolled back import cannot be removed
                 IllegalArgumentException + mesajda status → NOT_A_DRAFT değeri;
                 bilinmeyen id IllegalArgumentException → ALREADY_REMOVED
ImportDraftTest  a batch or cell that a game or task points at cannot be removed
                 SQLiteException "FOREIGN KEY" → HELD_BY_RECORDS
DraftTaskColorTest removing an import takes the colours of its drafts with it
ImportConfirmationStoreTest a confirmed import cannot be removed → store üzerinden NOT_A_DRAFT
CountingImportDao iki yeni protected üye outOfReach ile eklendi
```

### Dilim 2'nin bilerek YAPMADIKLARI

```text
kaldırma düğmesi / onay penceresi / Main bağlantısı   Dilim 4
D1–D9 sınıflandırması ve onay kapısı                  Dilim 3
import confirmation kapısı                            değişmedi
Room şeması / migration                               değişmedi (CASCADE'ler v3/v5/v8'den)
geçmiş olayı / otomatik snapshot                      YOK (PLAN 11.4.5, 14.4.7)
kesilmiş yedek artıklarının / JNI tmp'nin temizliği   yapılmadı
Dilim 1'in süreç/SIGKILL testleri                     değişmedi, zayıflatılmadı
R12, R13, R14                                         AÇIK kaldı
```

## İş 7 / Dilim 3'te uygulanan hâli

Önce ölçüm, sonra salt okunur sınıflandırma, sonra onay yoluna iki kapı. Room
şeması, migration zinciri, yedek okuyucusu, kaldırma motoru ve Dilim 1'in süreç
testleri değişmedi; PLAN değişmedi.

```text
commonMain/domain/importhealth/DraftHealth.kt          DraftContradiction (9 değer, D1–D9)
                                                       + DraftHealth (Sound / Contradicting /
                                                       NotFound / NotADraft)
commonMain/data/database/projection/DraftHealthRows.kt DraftHealthFacts (6 sayım, tek SELECT)
                                                       + SelectionCandidateRow + saf
                                                       draftHealthOf(batch, facts, selections)
commonMain/data/database/dao/ImportDao.kt              @Transaction draftHealthOf(batchId)
                                                       + iki protected sorgu
commonMain/data/repository/ImportConfirmationStore.kt  iki kapı (aşağıda)
commonMain/domain/importconfirm/ImportConfirmationFailure.kt
                                                       RECORDS_CONTRADICT_EACH_OTHER +
                                                       ImportConfirmationException.contradictions
ui/…/ConfirmationMessages.kt, ui/Strings.kt,           confirm_error_records_contradict
composeResources/values/strings.xml
```

### D1–D9 ulaşılabilirlik matrisi  *(ÖLÇÜLDÜ — gerçek restore hattı)*

Yöntem (`DraftContradictionReachTest`): gerçek store yollarıyla kurulmuş sağlıklı
bir veritabanı (`insertGameCellAndTask` + `aReadyDraftImport`: kaydedilmiş,
seçimle taslak kesilmiş, hedeflenmiş, onaylanabilir bir taslak) `BackupStore`
ile kanonik `BackupData` olarak okunur; `data class copy` ile **yalnız** hedef
çelişki eklenir; `backupDocumentOf` `dataSha256`'yı biçimin kendi fonksiyonuyla
yeniden hesaplar; bayt dizisi `FakeBackupInput` ile gerçek
`UntrustedBackupReader(TemporaryBackupProbe(…))`'a verilir (geçici dizinler test
tarafından yaratılır, probe bittiğinde silindiği doğrulanır); `Valid` dönerse
`LiveBackupRestorer` canlı geçici DB'ye yazar ve DB'nin yüklenen veriye DEĞER
düzeyinde eşit, iki PRAGMA'nın temiz olduğu doğrulanır. Doğrudan SQL ile
kurulmuş hiçbir durum bu matrise delil sayılmadı.

```text
kod  eklenen tek çelişki                                  okuyucu+probe  canlı DB  sınıflandırıcı
D1   created_task_count = 1                               Valid          ULAŞTI    {D1}
D2   created_game_count = 1                               Valid          ULAŞTI    {D2}
D3   raw_block_count = gerçek + 1                         Valid          ULAŞTI    {D3}
D3   raw_block_count = gerçek − 1                         Valid          ULAŞTI    {D3}
D4   taslağın materialized_task_id'si = mevcut görev      Valid          ULAŞTI    {D4}
D5   import_batch_cells(batch, mevcut hücre, "")          Valid          ULAŞTI    {D5}
D6   görevin source_raw_import_block_id'si = taslak bloğu Valid          ULAŞTI    {D6}
D6   aynısı, görev silinmiş (deleted_at dolu)             Valid          ULAŞTI    {D6}
D7   oyunun source_import_batch_id'si = taslak            Valid          ULAŞTI    {D7}
D7   aynısı, oyun silinmiş                                Valid          ULAŞTI    {D7}
D8   selection_end_index = metnin UTF-16 uzunluğu + 1     Valid          ULAŞTI    {D8}
D9   THREE_D blokta game_completion_hint = PENDING        Valid          ULAŞTI    {D9}
D9   THREE_D blokta ACCEPTED + completion_target_game_id  Valid          ULAŞTI    {D9}
kontrol  hiç çelişki yok                                  Valid          ULAŞTI    Sound; onay 1 görev + 1 snapshot
kontrol  materialized_task_id = var olmayan görev         Refused        ULAŞMADI  BROKEN_REFERENCE; canlı DB aynı
```

**Sonuç: ulaşılamayan predicate yok; PLAN 11.4.5'in listesi olduğu gibi doğru.**
Sınıflandırıcıya sahte veya gereksiz kural eklenmedi: dokuz neden, dokuz ölçülmüş
predicate'tir. İkinci kontrol satırı, ölçümün bir reddi görebildiğini gösterir
(her şeyi "ulaştı" diye raporlayan bir hat değil).

Her D satırında aynı test ayrıca şunları doğrular: sınıflandırıcı iki kez sorulur
ve **yalnız o nedeni** söyler, sorulması hiçbir satır yazmaz; onay
`RECORDS_CONTRADICT_EACH_OTHER` + aynı neden kümesiyle reddedilir, `snapshots.taken
== 0`, rotation çağrılmaz, DB değişmez, batch DRAFT kalır; Dilim 2 kaldırma motoru
PLAN'a göre davranır — D6/D7'de `HELD_BY_RECORDS` ve DB aynı, diğer yedisinde
`Removed` ve DB = önce − o taslak; sonrasında iki PRAGMA temiz.

### Sınıflandırıcı kuralları  *(gerçek hâli)*

```text
DraftContradiction                      predicate (b = DRAFT batch)            nereden
TASKS_COUNTED_BEFORE_CONFIRMATION   D1  b.created_task_count != 0             batch satırı
GAMES_COUNTED_BEFORE_CONFIRMATION   D2  b.created_game_count != 0             batch satırı
RAW_CELL_COUNT_DISAGREES            D3  b.raw_block_count != blok satırı sayısı  facts
DRAFT_ALREADY_MATERIALIZED          D4  b'nin taslağında materialized_task_id  facts
CELL_RECORDED_BEFORE_CONFIRMATION   D5  import_batch_cells'te b               facts
TASK_SOURCED_FROM_DRAFT             D6  tasks → b'nin bloğu (silinmiş dâhil)  facts
GAME_SOURCED_FROM_DRAFT             D7  games → b (silinmiş dâhil)            facts
SELECTION_BEYOND_ITS_TEXT           D8  selection_end_index > rawText.length  aday satırlar + Kotlin
COMPLETION_HINT_OUTSIDE_GAME_COLUMN D9  source_column_type <> 'GAME' AND
                                        game_completion_hint <> 'NONE'        facts
```

- `draftHealthOf(batchId)` bir `@Transaction`'dır (ölçüldü: ilk okumadan önceki son
  işaret `BEGIN IMMEDIATE TRANSACTION`). Sebep: batch satırı ile sayımlar ayrı
  okunsa, arada commit edilen bir kaldırma sağlıklı bir taslağı D3 gibi
  gösterebilirdi. Hiçbir satır yazmaz.
- Batch yoksa `NotFound`, DRAFT değilse `NotADraft(status)` — ikisi de yalnız batch
  okumasıyla, çocuk satırlara bakılmadan. `CONFIRMED`/`ROLLED_BACK` tutarsızlıkları
  sınıflandırılmaz (R14).
- **D8 neden iki adımda:** SQLite `length()` kod noktası sayar (ve ilk NUL'da durur);
  seçim UTF-16 birimiyle indekslenir. UTF-16 uzunluğu hiçbir zaman kod noktası
  sayısından kısa olmadığı için SQL süzgeci `selection_end_index > length(raw_text)`
  gerçek D8 satırlarının **üst kümesini** döndürür; kesin karar Kotlin'de
  `selectionEndIndex > rawText.length` ile verilir. Ölçüldü: "🎲🎲" (2 kod noktası,
  4 UTF-16) için son = 4 Sound, 3 (vekil çiftin ortası) Sound, 5 Contradicting;
  Kotlin daraltması kapatılınca 2 test düşüyor.
- Programlama hatası maskelenmez: DAO hiçbir şey yakalamaz; bozuk taslak bir
  **cevaptır**, exception değildir. `Contradicting` boş neden kümesiyle, `NotADraft`
  DRAFT durumuyla kurulamaz (`require`).
- Kaynak dosya okunmaz; sınıflandırıcının dosya sistemiyle hiçbir bağı yoktur
  (smoke: kaynak silinmiş / yeniden yazılmış CSV taslağı Sound, dosya listesi ve
  parmak izi aynı).

Bozuk **sayılmadığı** testle sabitlenenler (`DraftHealthTest`): uygulamanın kendi
taslağı (çok bloklu, seçimli, hazır), sıfır bloklu DRAFT, blokları olup taslağı
olmayan ve hiç işlenmemiş DRAFT, silinmiş oyundaki hedef, Migration3To4'ün
temizlediği hedef/materialized, geriye giden zaman damgaları, GAME sütununda
PENDING / hedefsiz ACCEPTED / hedefli ACCEPTED / REJECTED, grapheme ortasına düşen
seçim, metnin tam sonunda biten seçim. Ayrıca: dokuz çelişkinin hepsini taşıyan bir
taslağın yanındaki komşu taslak Sound kalır (değerlendirme batch'e özeldir).

### İki onay kapısı

```text
confirm(batchId)
 1  KAPI 1  importDao.draftHealthOf(batchId)            salt okunur, snapshot'tan ÖNCE
            Contradicting → RECORDS_CONTRADICT_EACH_OTHER; snapshot YOK, rotation YOK,
                            yazma YOK
            SQLiteException → COULD_NOT_SAVE (snapshot YOK)
            ISE / IAE / diğer → olduğu gibi yükselir
            Sound / NotFound / NotADraft → devam (bu sonuçlar ESKİ davranışa bırakılır:
                            BATCH_NOT_FOUND, ALREADY_CONFIRMED vb. confirmDraftBatch'ten)
 2  snapshots.takeBeforeImport()  → housekeeping      (değişmedi)
 3  BEGIN IMMEDIATE
 4    15 tablo okunur, snapshot.data ile karşılaştırılır → farklıysa DATA_CHANGED_MEANWHILE
 5  KAPI 2  importDao.draftHealthOf(batchId)            aynı transaction, ilk yazımdan ÖNCE
            Contradicting → ContradictsNow → RECORDS_CONTRADICT_EACH_OTHER (cause dolu);
                            transaction geri alınır, hiçbir domain yazımı yapılmamıştır
            SQLiteException → COULD_NOT_SAVE;  ISE / IAE → olduğu gibi
 6    confirmDraftBatch                                 (değişmedi)
    COMMIT
```

Kapı 2'nin yakaladığı durum: çelişki kapı 1'den **sonra**, snapshot'tan **önce**
oluşur → snapshot bozuk hâli tarif eder, 15 tablo karşılaştırması geçer, yalnız kapı
2 durdurabilir (test: snapshot alıcısı gerçek snapshot'tan hemen önce
`created_task_count`'u bozar → RECORDS_CONTRADICT_EACH_OTHER, 1 snapshot, DB =
snapshot'ın verisi, batch DRAFT). Snapshot'tan **sonra** oluşan bozulma zaten
mevcut yarış korumasıyla `DATA_CHANGED_MEANWHILE` alır (testli, değişmedi).
Mutasyonla doğrulandı: kapı 1 kapatılınca 17 test (snapshot sayısı ve disk
iddiaları), kapı 2 kapatılınca kapılar-arası test düşüyor.

`ImportConfirmationStore` zaten `Main`'e bağlı olduğu için kapılar **bugün
kullanıcının onay yolunda çalışıyor**; ayrı bir bağlantı gerekmedi.

### Tipli sonuç ve kullanıcıya görünen

```text
ImportConfirmationFailure.RECORDS_CONTRADICT_EACH_OTHER    toplu (batch-wide) ret
ImportConfirmationException.contradictions: Set<DraftContradiction>
                                                           yalnız store bu ret için doldurur;
                                                           ekran KULLANMAZ, mesaja girmez
confirm_error_records_contradict
  "Bu taslağın kayıtları birbiriyle uyuşmadığı için onaylanamıyor. Hiçbir şey
   yazılmadı ve taslak olduğu gibi duruyor. İnceleme ekranındaki bir düzenleme bunu
   düzeltemez; böyle bir taslağı kaldırma seçeneği ileride İçe Aktarma ekranına
   eklenecek."
```

Cümle yalnız onay denendiğinde görünür. Dilim 3'ün plan metni "kaldırma eylemini
vaat etmez" diyordu; dilim talimatı "Dilim 4'te kaldırılabileceği anlaşılır
biçimde söylenebilir; düğme ekleme" dediği için **talimat uygulandı**: cümle bir
sonraki eylemi söyler, düğme yoktur. Ekran testi D-kodu, tablo/sütun adı, enum
adı, `SELECT`, `Exception` ve batch UUID'sinin görünmediğini doğrular.

### Ölçülen sorgu sayısı

```text
draftHealthOf, DRAFT — 1 blok / 42 blok × 2 taslak (sağlıklı)          AYNI harita:
  SELECT batch 1 · SELECT altı sayım 1 · SELECT aday seçimler 1
aynısı, 84 seçimin hepsi metnin dışında (aday sorgusu 84 satır döner)  AYNI harita
NotFound / NotADraft                                                   SELECT batch 1
ilk okumadan önceki son transaction işareti                            BEGIN IMMEDIATE TRANSACTION
store onayı — 1 blok ve 42 blok                                        sınıflandırıcı TAM 2 kez
mevcut ImportConfirmationQueryCountTest                                değişmeden geçti
```

### Testler

```text
data/database/DraftContradictionReachTest.kt   15  ölçüm matrisi (13 çelişki + 2 kontrol);
                                                   her satırda sınıflandırıcı, onay reddi,
                                                   snapshot 0, kaldırma motoru, PRAGMA'lar
data/database/DraftHealthTest.kt               15  bozuk sayılmayanlar, UTF-16 kenarları,
                                                   komşu izolasyonu, dokuzu birden, saf
                                                   fonksiyonun her nedeni tek başına,
                                                   yapıcı invariant'ları
data/repository/ImportConfirmationGateTest.kt  11  iki kapı: sağlıklı onay 2 bakış + 1 snapshot;
                                                   kapı 1 reddi (snapshot/rotation/yazma 0);
                                                   tekrar reddi; kapılar arası bozulma kapı 2;
                                                   snapshot sonrası bozulma DATA_CHANGED;
                                                   üç neden birlikte; SQLiteException kapı 1
                                                   ve 2 → COULD_NOT_SAVE; ISE kapı 1 ve IAE
                                                   kapı 2 maskelenmez; diğer retler eskisi gibi
data/repository/DraftHealthQueryCountTest.kt    4  yukarıdaki sorgu ölçümü
platform/recovery/DraftHealthSmokeTest.kt       4  geçici XDG + StartupGate: kaynak dosyası
                                                   silinmiş / yeniden yazılmış CSV taslağı
                                                   Sound, parmak izi ve dosya listesi aynı;
                                                   bozuk taslak GERÇEK VerifiedSnapshotTaker'lı
                                                   store ile reddedilir, backups/ BOŞ,
                                                   settings.json yok, yeniden açılış
                                                   assertWhole; kontrol: aynı taslak bozulmadan
                                                   onaylanır ve 1 yedek dosyası yazılır
ui/…/ImportReviewScreenTest.kt                 +1  Türkçe cümle + sızıntı taraması
```

Bilinçli olarak değiştirilen eski test: `ImportConfirmationRollbackTest`'in
fixture'ı `rawBlockCount = drafts` yazıp `drafts + 2` blok ekliyordu — yani
**kendisi D3 taşıyan** bir taslaktı. Store üzerinden giden tek testi ("a clock
that will not answer…") yeni kapıya takıldı. Fixture sayımı doğru olacak biçimde
düzeltildi (`drafts + 2`); testin iddiası değişmedi. `CountingImportDao` iki yeni
protected üyeyi `outOfReach` ile aldı.

### Dilim 3'ün bilerek YAPMADIKLARI

```text
Kaldır düğmesi / onay penceresi / bozuk taslak bölümü     Dilim 4
bütün DRAFT'ları tek seferde sınıflandırma (liste için)   Dilim 4 — bugünkü API batch başına
                                                          3 okumadır; liste N batch için
                                                          N × 3 yapmamalı. Saf
                                                          draftHealthOf(batch, facts, selections)
                                                          gruplanmış okumalarla beslenebilir
inceleme ekranının bozuk taslağı açmayı reddetmesi        Dilim 4 (PLAN 11.4.5)
otomatik silme / onarma / yeniden numaralandırma          YOK (karar 3, 8)
yedek okuyucusunu sıkılaştırma                            YOK → R14
açılışta denetim                                          YOK
inceleme yazımlarına yeni denetim                         YOK
Room şeması / migration                                   değişmedi
R12, R13, R14                                             AÇIK kaldı
```

## İş 7 / Dilim 4'te uygulanan hâli

Arayüz. Kaldırma motoru (Dilim 2), sınıflandırıcı ve onay kapıları (Dilim 3)
yeniden tasarlanmadı; yalnız kullanıldı. Room şeması, migration zinciri, yedek
okuyucusu ve Dilim 1'in SIGKILL testleri değişmedi; PLAN değişmedi.

```text
commonMain/data/database/dao/ImportDao.kt              @Transaction healthOfDraftBatches() + iki
                                                       protected toplu sorgu; draftBatches()
                                                       sırası `imported_at DESC, id` (listeyle aynı)
commonMain/data/database/projection/DraftHealthRows.kt DraftBatchHealthRow, DraftBatchSelectionRow,
                                                       DraftBatchHealth
commonMain/data/repository/UnfinishedImportsStore.kt   UnfinishedImport, UnfinishedImportsUnreadable,
                                                       UnfinishedImports arayüzü + store
commonMain/ui/feature/importworkspace/
  UnfinishedImportsState.kt                            liste / açma / kaldırma durumları, RowFocus
  UnfinishedImportsController.kt                       tek yüzey, taze denetim, çift gönderim, odak
  UnfinishedImportsSection.kt                          iki liste, onay penceresi, sonuç pencereleri
  ImportSection.kt                                     ResumableImports → UnfinishedImportsSection
  ImportReviewController.kt                            artık kullanılmayan draftBatches listesi kaldırıldı
ui/PnpTrackerApp.kt, ui/navigation/AppScaffold.kt,     controller bağlandı; restore sonrası
desktopMain/Main.kt                                    StaleSurfaces listesinde
Strings.kt, strings.xml                                33 yeni Türkçe metin (Strings.Unfinished);
                                                       eski review_resumable_hint kaldırıldı;
                                                       Dilim 3'ün onay reddi cümlesi artık var olan
                                                       kaldırmayı "deneyebilirsiniz" diye gösterir
```

### Toplu sağlık okuması  *(ÖLÇÜLDÜ)*

```text
BEGIN IMMEDIATE                     (@Transaction; sıra ile ölçüldü)
1  SELECT * FROM import_batches WHERE status = 'DRAFT' ORDER BY imported_at DESC, id
   boşsa → [] (tek okuma)
2  her DRAFT batch için altı sayım, batch satırına bağlı alt sorgularla TEK ifadede
3  bütün DRAFT'ların SQLite length()'i aşan seçim adayları TEK ifadede (batch_id ile)
→ her batch için Dilim 3'ün SAF draftHealthOf(batch, facts, selections)'ı
COMMIT
```

- 1 taslak ve 42 taslak (her biri 5 hücre × 2 taslak, 84 seçimin hepsi metin
  dışında) AYNI haritayı çalıştırır: `drafts 1 · counts 1 · selections 1`; yazma 0.
  Store üzerinden bir liste okuması da aynı üç ifadedir.
- Tekil ↔ toplu eşdeğerlik: dokuz çelişkinin her birini tek başına taşıyan dokuz
  taslak + iki sağlıklı taslak ve dokuzunu birden taşıyan bir taslak için
  `healthOfDraftBatches()` ile `draftHealthOf(id)` her satırda eşit (testli;
  mutasyonla: toplu D6 sayımı bozulunca 5 test düşüyor).
- Tazelik: `database.invalidationTracker.createFlow(import_batches,
  raw_import_blocks, draft_tasks, import_batch_cells, tasks, games)` her
  değişiklikte transaction içindeki okumayı yeniden yapar. Room'un gözlenen
  sorgusu yalnız kendi tablolarını izlediği için yetmezdi: yalnız `tasks`'a
  yazılan bir D6 başvurusu da listeye ulaşır (testli).
- SQLiteException → `UnfinishedImportsUnreadable` (liste "okunamadı" der);
  programlama hataları yakalanmaz.

### Kullanıcı akışı

```text
İçe Aktarma
  Devam eden içe aktarmalar          geçerli taslaklar: [Devam et] [Kaldır]
  Kayıtları uyuşmayan taslaklar      ayrı başlık + Türkçe uyarı: [Kaldır] YALNIZ
                                     (D6/D7 taşıyorsa "kaldırma reddedilebilir" notu)
[Devam et] → "Taslak denetleniyor…" → draftHealthOf(id) YENİDEN okunur
     Sound            → inceleme ekranı (kaynak dosya okunmaz)
     Contradicting    → açılmaz; "…kayıtları birbiriyle uyuşmuyor; bu yüzden açılmadı…"
     NotFound         → açılmaz; "…artık yok…"
     NotADraft        → açılmaz; "…artık bir taslak değil…"
     okunamadı        → açılmaz; "…okunamadı… tekrar deneyin"
[Kaldır] → onay penceresi, odak Vazgeç'te; PLAN 11.4.5'in dört cümlesi:
     hangi dosyanın taslağı · ham hücreler + görev taslakları kalıcı silinir ·
     hiçbir oyun/görev/hücre değişmez · geri alınamaz
  [Kaldır] → "Taslak kaldırılıyor…" (Escape, dışarı tıklama, Vazgeç ETKİSİZ;
              ikinci basış yok sayılır) → motorun cevabı:
     Removed           "N ham hücre ve M görev taslağı silindi…"
     ALREADY_REMOVED   "zaten kaldırılmış…" (hata rengiyle DEĞİL, sessiz başarı DEĞİL)
     NOT_A_DRAFT       "artık taslak değil… Onaylanmış içe aktarmalar listesinden…"
     HELD_BY_RECORDS   ayrı cümle: "gerçek kayıtlar bu taslağa bağlı olduğu için…"
     COULD_NOT_SAVE    "kaldırılamadı; hiçbir şey değişmedi…"
  kapanınca odak   iptal/ret → aynı satırın Kaldır'ı; başarı → sonraki satırın ilk
                   düğmesi (son satırsa bir öncekinin); liste boşaldıysa hiçbiri
```

Kararlar ve bilinçli sapmalar:

- Düğme adı plan metnindeki `Aç` yerine `Devam et`: PLAN 11.4.5 seçeneği "devam
  eder" diye adlandırır ve dilim talimatı da bu sözcüğü kullanır. Erişilebilir
  adlar dosya adını taşır (`<dosya> taslağına devam et`, `<dosya> taslağını kaldır`).
- Liste tavsiye niteliğindedir; hiçbir karar ondan verilmez. Onay açıkken liste
  değişirse soru kapanmaz; basış motorun tipli cevabını (ör. ALREADY_REMOVED)
  gösterir.
- Kaldırma geçmişe olay yazmaz, snapshot almaz (store bir snapshot alıcısı veya
  saat almaz); smoke'ta `backups/` boş, `history_events` boş.
- `ImportReviewController.draftBatches` / `observeDraftBatches` üretimde tek
  kullanıcısını kaybettiği için kaldırıldı ve onu doğrulayan tek controller
  testi bu sorumluluk `UnfinishedImportsControllerTest`'e geçtiği için
  kaldırıldı. `ImportReview.observeDraftBatches()` (store arayüzü) kendi store
  testiyle yerinde bırakıldı; üretimde çağıranı yok (§33 R3).

### Testler

```text
data/repository/UnfinishedImportsStoreTest.kt        7  tekil↔toplu eşdeğerlik (9 tekil + dokuzu
                                                        birden + sağlıklılar), sıra ve yalnız
                                                        DRAFT, held işareti, altı tablonun
                                                        tazeliği, motor üzerinden kaldırma (HELD,
                                                        Removed, ALREADY_REMOVED, history 0),
                                                        taze sağlık, SQLiteException → tipli
data/repository/UnfinishedImportsQueryCountTest.kt   3  1 = 42 taslak üç okuma; store okuması;
                                                        boş liste tek okuma
commonTest/…/UnfinishedImportsControllerTest.kt     13  bölme, okunamayan liste, taze denetim,
                                                        bayat liste (4 ret), ISE maskelenmez,
                                                        denetim sürerken ikinci açma/soru yok,
                                                        tek transaction, her ret, kaldırma
                                                        defekti maskelenmez, odak jetonları,
                                                        liste kayması, restore bırakma
commonTest/…/UnfinishedImportsDoubles.kt                FakeUnfinishedImports (kapılı çağrılar)
desktopTest/…/UnfinishedImportsSectionTest.kt       12  gerçek Compose sahnesi: iki bölüm, bozukta
                                                        Devam et YOK, held notu, boş/okunamaz,
                                                        taze denetimden sonra açma, bayat taslak
                                                        açılmaz, Tab + tekrarlanan Enter tek
                                                        denetim, dört cümle + odak Vazgeç, Enter
                                                        iptal eder, Escape kapatır ve odağı geri
                                                        verir, kaldırma sürerken hiçbir şey iki
                                                        kez başlamaz ve kapanmaz, başarı + odak
                                                        sonraki satırda, dört ret cümlesi, 640×620
                                                        pencere 2× yoğunlukta düğmeler ekranda;
                                                        sızıntı taraması (enum, D-kodu, tablo,
                                                        SQL, UUID, yol, Exception)
platform/recovery/UnfinishedImportsSmokeTest.kt      5  geçici XDG + StartupGate + gerçek store'lar:
                                                        CSV kaynağı SİLİNMİŞ → devam (inceleme
                                                        gerçek hücreleri okur) + kaldırma; XLSX
                                                        (anonim fixture'ın kopyası) kaynağı
                                                        YENİDEN YAZILMIŞ → devam + kaldırma;
                                                        kaynağı silinmiş taslak devam + GERÇEK
                                                        yedekli onay; SIGKILL ile öldürülmüş
                                                        süreçten kalan taslak listelenir, devam,
                                                        kaldırma; D1 ve D6 taşıyan iki taslak ayrı
                                                        bölümde, açılmaz, D6 HELD_BY_RECORDS ve
                                                        parmak izi aynı, D1 kaldırılır. Her yolda
                                                        backups/ boş (onay dışında), history boş,
                                                        dosya listesi aynı, yeniden açılış
                                                        assertWhole, parmak izi İÇE AKTARMA
                                                        ÖNCESİ değere eşit
```

Mutasyonla doğrulandı: açma denetimi çelişkiyi yok sayınca 3 test, toplu okuma
D6'yı kaybedince 5 test, bozuk satıra `Devam et` konunca 1 test düşüyor.

### PLAN Faz 3 kabul maddeleri — İş 7 kapanışı

```text
commit edilmiş taslak SIGKILL sonrası listede            UnfinishedImportsSmokeTest
kaynak silinmiş/değişmişken açılır, onaylanır, kaldırılır DraftHealthSmokeTest + UnfinishedImportsSmokeTest
bozuk taslak açılıştan sonra kendiliğinden silinmez,       UnfinishedImportsSmokeTest (her adımda
  açılışı engellemez                                       gate'ten yeniden açılış; HELD taslak kalır)
kaldırma ve bozuk taslak uyarısı Türkçe, sızıntısız       UnfinishedImportsSectionTest
klavye, odak, Escape/Vazgeç, dar pencere, 2× DPI          UnfinishedImportsSectionTest
çift gönderim, bayat liste, tipli hatalar                 Controller + Section testleri
kaldırma snapshot/history üretmez                         StoreTest + SmokeTest
diğer İş 7 maddeleri                                      Dilim 1–3 testleri (değişmedi)
```

Bu tabloya göre İş 7'nin bütün kabul ölçütleri karşılanmıştır. Gerçek bir
pencerede manuel tur (geçici XDG ile `./gradlew run`) bu turda **yapılmadı**:
kullanıcı başında değildi ve ekran otomasyonu yapılmadı; ekran davranışı gerçek
Compose sahnesinde test edildi.

### Dilim 4'ün bilerek YAPMADIKLARI

```text
açılışta bildirim/engelleme, "kurtarma" ekranı          YOK (karar 1, 7)
toplu kaldırma                                          YOK
kaynak dosyayı yeniden okuma                            YOK
ham .db geri yükleme                                    YOK (karar 9)
kaldırma history satırı / snapshot                      YOK
inceleme ekranı açıkken arkada bozulan taslak           ele alınmadı: onay kapıları korur
R12, R13, R14                                           AÇIK kaldı
```

## Reddedilen alternatifler  *(tekrar önerilmesin)*

```text
clean-shutdown işaret dosyası            REDDEDİLDİ  SQLite zaten atomik; işaret yeni bir
                                                     tutarsızlık kaynağı olurdu
-wal varlığını çökme sayma               REDDEDİLDİ  WAL normal çalışma hâlidir
açılışta kurtarma ekranı / bildirim      REDDEDİLDİ  tespit edilecek yarım durum yok
DRAFT'ı otomatik silme / onaylama        REDDEDİLDİ  karar 3
kaynak dosyayı yeniden okuyup "onarma"   REDDEDİLDİ  karar 5; ham bloklar esastır
bozuk taslağı düzeltme / yeniden numara  REDDEDİLDİ  karar 8
açılışta bozukluk denetimi               REDDEDİLDİ  açılış engellenmez; denetim ekranda
ham .db için uygulama içi restore        REDDEDİLDİ  karar 9
integrity_check hatasında otomatik iş    REDDEDİLDİ  karar 10 → R13
yedek okuyucusunu İş 7'de sıkılaştırma   ERTELENDİ   zaten geri yüklenmiş bir DB'de her
                                                     import snapshot'ını fail closed yapar → R14
kaldırmada otomatik snapshot             REDDEDİLDİ  PLAN 14.4.7'nin tetikleyici listesi kapalı
kaldırma için history olayı              REDDEDİLDİ  PLAN 12.15'te yok; şema değişirdi
synchronous = FULL yapmak                REDDEDİLDİ  İş 7 ayarı değiştirmez; ayrı karar
bozukluk denetimini yalnız transaction   REDDEDİLDİ  bozuk taslak için snapshot + rotation
  içinde yapmak                                      çalışırdı (PLAN 14.4.8: başarı ihtimali
                                                     olmayan onaya snapshot alınmaz)
ölçülmemiş / ulaşılamayan predicate      REDDEDİLDİ  dokuzu da ölçüldü; hayalî dedektör yok
bozukluk nedenini kullanıcıya göstermek  REDDEDİLDİ  D-kodu/tablo/UUID ekrana girmez; tek cümle
D8'i SQLite length() ile kesinleştirmek  REDDEDİLDİ  kod noktası sayar, UTF-16 değil
listede satır başına draftHealthOf       REDDEDİLDİ  N × 3 okuma; toplu okuma aynı saf fonksiyonla
listeye güvenip doğrudan açmak           REDDEDİLDİ  bayat liste bozuk/kaldırılmış taslağı açardı
kaldırmayı her zaman mümkün göstermek    REDDEDİLDİ  D6/D7'de motor reddeder; ayrı cümle var
```

---

# 25.4 TANILAMA VE VERİ BÜTÜNLÜĞÜ HATA SEMANTİĞİ  *(Faz 3 / İş 10 — 8/8 dilim, TAMAMLANDI)*

Bağlayıcı metin PLAN `14.7` (ve ona bağlanan `14.2`, `11.4.5`, `14.4.7`,
`14.4.10`, `14.4.11`, `14.4.13`, `16.`, `18.` Faz 3 İş 10 ve testleri). Bu bölüm
kararların özetini, dayandıkları **repo kanıtlarını** ve dilim tablosunu tutar.
Belge turu kod ve test **değiştirmedi**, test **çalıştırmadı**; aşağıdaki
"kanıt" satırları kod okumasıdır, "ölçüldü" yazmayan hiçbir şey ölçülmemiştir.

## Verilmiş kararlar  *(yeniden tartışılmaz)*

```text
 1  İş 9 kabulü makineden bağımsız; süre/bellek eşik değil, ortamla kayıt; belirgin
    kötüleşme (aynı yöntemde kaydın 2 katı) raporlanır, test düşmez → İş 9 TAMAM
 2  yeni bağımlılık YOK: java.nio + mevcut kotlinx-serialization-json
 3  yer $XDG_STATE_HOME/pnp-tracker/logs/ (fallback ~/.local/state); data, backups,
    config alanlarına log dokunmaz; dizin/dosya ilk kayıtta oluşur (0700/0600)
 4  UTF-8 JSON Lines, satır = tek olay, ≤ 2 KiB, kayıt sürümü v = 1
 5  alanlar v, seq, at, level, event, app, schema + tipli isteğe bağlılar
    (reason, area, place, fromSchema, toSchema, count, exception, cause)
 6  seviyeler yalnız INFO / WARN / ERROR; olay kodları kapalı liste (PLAN 14.7.2)
 7  yazılmaz: kullanıcı metni, içe aktarılan/yedekten okunan değer, DOSYA ADI, yol,
    kullanıcı/makine adı, ortam değişkeni, UUID/kimlik, SQL, PRAGMA çıktısı,
    exception mesajı, stack trace, suppressed; API metin parametresi ALMAZ
 8  exception: yalnız sınıfın ve kök nedenin tam sınıf adı, kalıba uymazsa "?"
 9  kayıt çağıranı bekletmez, exception fırlatmaz; 256'lık kuyruk, düşürülen sayılır;
    tek yazıcı iş parçacığı; fsync yok; 3 ardışık hata → süreç boyunca kapalı
10  tek yazıcı süreci: ayrı OS kilidi pnp-tanilama.lock (instance kilidine
    güvenilmez — R15)
11  rotation 5 dosya × 1 MiB, sabit adlar, yalnız NOFOLLOW normal dosyaya dokunur,
    bilinmeyen/symlink/dizin → dur ve kaydı kapat
12  kayıt, hatanın tipli sonuca çevrildiği yerde TEK KEZ; doğrulama geri bildirimi
    ve başarı kaydedilmez; yalnız iki INFO: startup.migration_completed,
    restore.completed
13  beklenmeyen hata maskelenmez; bugünkü davranışı Dilim 2'de ölçülür ve korunur
14  R12: zaman sırası bütünlük kuralı değil; epoch aynen; ters/eşit zamanda sıra id
    ile çözülür; rotation az önce yazılanı SİLMEZ (kota yeni dahil)
15  R13: açılışta Room'dan önce salt okunur PRAGMA quick_check; geçmezse
    DATABASE_DAMAGED, dosyalara dokunulmaz; integrity_check/foreign_key_check
    açılışa eklenmez; uygulama içi kurtarma YOK
16  R14: okuyucu SIKILAŞTIRILMAZ; adaylar ölçülür (L); geri alma C2–C4'te
    PROVENANCE_BROKEN; kullanıcının seçtiği yedek onaydan önce L ile bellekte
    denetlenir → IMPORT_RECORDS_CONTRADICT
17  sekiz atomik dilim; Room şeması, migration zinciri, bağımlılık DEĞİŞMEZ
```

## Repo kanıtları

```text
konu                         kanıt (dosya)                                       sonuç
XDG yolları                  XdgAppPathsResolver: yalnız XDG_DATA_HOME ve         state alanı yok → Dilim 1
                             XDG_CONFIG_HOME; AppDirectoryInitializer data,
                             backups, config dizinlerini açılışta oluşturur
                             ve hata mesajına MUTLAK YOL yazar
exception mesajı güvensiz    AppDirectoryInitializer (yol), XdgAppPathsResolver   mesaj/stack asla yazılmaz
                             (user.home değeri), ImportDao geri alma
                             postcondition'ları (görev/hücre kimliği),
                             UnconvertibleLegacyDataException (sayılar)
mevcut log/handler           üretimde println, System.err, printStackTrace,       sıfırdan tasarım
                             UncaughtExceptionHandler, CoroutineExceptionHandler
                             YOK
instance kilidi              StartupGate.open: lock.withLock { … } açılış         R15; ayrı log kilidi
                             bitince bırakır (PLAN 14.4.10 madde 16)
tipli hata aileleri          StartupProblem 8, BackupProblem 25, RestoreProblem  olay matrisi bunlara
                             6, SnapshotProblem 3, BackupFailure 9, ExportFailure  bağlanır
                             7 (+ExportInvariant 5), ImportFailure 23,
                             ImportConfirmationFailure 24, ImportRollbackFailure
                             8, DraftRemovalRefusal 4, SettingsProblem 4,
                             SettingsWriteFailure 2, XlsxReadFailure 7 ve
                             COULD_NOT_SAVE taşıyan altı düzenleme ailesi
housekeeping sonucu          SettingsDrivenHousekeeping.afterWriting             backup.rotation_incomplete
                             RotationOutcome'u (couldNotRemove) atıyor           kaydı Dilim 2
tipsiz kaçan sınır adayları  ImportController → ImportDraftStore.save            Dilim 2 ölçer,
                             SQLiteException yakalanmıyor;                       Dilim 3 tipler
                             GameTableController.observeTable,
                             ColorCatalogueController.observeColors,
                             PoolController.observeColorCatalogue: okuma hatası
                             durumu yok; PoolController.observePool
                             `.catch { emit(null) }` programlama hatasını da
                             Failed yapıyor; Main yalnız StartupRefused
                             yakalıyor (dizin/ev dizini hatası ham)
genel catch                  HistoryController.observeHistory `catch Exception`  kaydı storage.read_failed
R12 okuyucu                  BackupValues.checkWrittenAndChanged (7 tablo) ve     Dilim 4 kaldırır
                             importBatches updatedAt < importedAt → DOMAIN_INVARIANT
R12 yanlış gerekçe           BackupValues KDoc ve BackupValidationTest "ters      11.4.4 ölçütü != olduğu
                             zaman geri alınamazı alınabilir gösterir" diyor;    için ters zaman EDITED
                             ImportRollbackPlan.obstacleOf: updatedAt !=         sayılır → gerekçe yanlış
                             createdAt → EDITED
R12 entity                   EntityTimestamps init updatedAt >= createdAt,        üretimde kullanılmıyor
                             deletedAt >= createdAt (yalnız kendi testi)          (grep); Dilim 4 hizalar
R12 entity'ler               Room entity init'lerinde zaman sırası şartı YOK      geçici DB kural eklemez
R12 sıralar                  ImportDao.batchesWithFingerprint ve allBatches       Dilim 4: id eklenir
                             "ORDER BY imported_at DESC" ikincil anahtarsız;
                             diğer bütün zaman sıraları id ile kararlı
R12 rotation                 AutomaticBackupRotation.surplusOf stamp/attempt/     az önce yazılan silinebilir
                             setName DESC + drop(keep); justWritten yalnız        (kod okuması) → Dilim 4
                             varlık denetimi
R13 bugün                    ConsistentDatabaseClone: salt okunur bağlantı,       quick_check buraya eklenir
                             schemaVersionOf; isWholeAndConsistent yalnız klonda
R14 okuyucu paylaşımı        UntrustedBackupReader: RestoreController,            sıkılaştırma snapshot'ı ve
                             VerifiedSnapshotTaker, MigrationSnapshotSetWriter    açılışı kilitler → REDDEDİLDİ
                             aynı sınıfı kullanır; manuel yedek ve güvenlik
                             yedeği geri okunmaz
R14 statü yazıcıları         markBatchConfirmed (WHERE status='DRAFT'),          geçişler tek yönlü
                             markBatchRolledBack (WHERE status='CONFIRMED');
                             insertBatchCell yalnız confirmDraftBatch;
                             Migration7To8 tabloyu boş oluşturur
R14 onay                     confirmDraftBatch: created_task_count =             C1–C5 adayları
                             drafts.size, her taslağa materialized, taskRow
                             sourceRawImportBlockId = taslağın bloğu, hedef
                             hücre başına bir import_batch_cells satırı,
                             require(createdGameCount == 0)
R14 eski veri                Migration3To4: materialized_task_id temizlenir,      created_task_count ilişkisi
                             görevler düşer (üretim satırı varsa ret),            eski batch'te KURAL DEĞİL
                             import_batches korunur
R14 geri alma                planImportRollback: provenanceHolds =                C3/C4 denetlenmiyor
                             drafts > 0 ∧ materialized = drafts ∧ tasks = drafts; → Dilim 6
                             obstacleOf: silinmiş, çapasız, kayıtsız hücre,
                             EDITED, progress, history
R14 FK                       8.json: materialized_task_id, source_raw_import_     silme yolu olmadığından
                             block_id, source_import_batch_id RESTRICT;           bağlantılar kalıcı
                             raw_import_blocks/import_batch_cells CASCADE
```

## Olay matrisi — özet

PLAN `14.7.2` tablosu bağlayıcıdır. Kısaca: açılış reddi (ERROR, ANOTHER_COPY →
WARN), migration bitişi (INFO), ayar okuma/yazma sorunu (WARN), depolama okuma
ve yazma hatası (ERROR, `area` + sınıf adları), içe aktarma dosyasının
okunamaması (yalnız okuyucu/ortam arızası, WARN), onay snapshot'ı (ERROR),
arada değişen veri ve çelişen kayıtlar (WARN), geri almada provenance ve taslak
kaldırmada gerçek kayıtlar (WARN), manuel yedek yazımı (WARN), rotation eksik
kalması (WARN, `count`), geri yükleme dosya reddi (WARN, `reason`+`place`) ve
tamamlanmaması (ERROR), geri yükleme başarısı (INFO), CSV yazımı (WARN) ve bozuk
veri (ERROR), beklenmeyen hata (ERROR), düşürülen kayıt (WARN).

**Kaydedilmeyenler:** sütun düzeni ve CSV dilbilgisi hataları, hedef/havuz/renk
seçimi eksikleri, vazgeçme, dokunulmuş görev veya değişmiş hücre yüzünden geri
alma engeli, eski batch'in `NO_CELL_SNAPSHOT`'ı, boş dışa aktarma, uygulamanın
her açılışı, her başarılı düzenleme.

## R14 aday invariant'ları — sınıflandırma

```text
DRAFT                 ¬D1…¬D9                                    KESİN (ölçüldü, İş 7)
CONFIRMED, cells boş  ilişki iddia edilmez                      KURAL DEĞİL (Migration3To4
                                                                ve şema 8 öncesi onaylar)
CONFIRMED, cells dolu C1 made = drafts ≥ 1                      KESİN ADAY
                      C2 created_task_count = drafts            KESİN ADAY
                      C3 task(d).source block = d'nin bloğu     KESİN ADAY (zarar: geri alma)
                      C4 cells = taslak hedefleri, boş hedef yok KESİN ADAY (zarar: geri alma)
                      C5 created_game_count = 0, claimed boş    KESİN ADAY
ROLLED_BACK           RB1 cells dolu · RB2 C1–C5 · RB3 görevler  KESİN ADAY
                      silinmiş · RB4 segment yok · RB5 görev
                      başına TASK_ROLLED_BACK
her statü             U1 hint yalnız GAME sütununda · U2 seçim  ADAY (eski sürümde
                      ≤ UTF-16 uzunluk · U3 raw_block_count     ölçülmedi)
                      = blok sayısı
                      → Dilim 5 ÖLÇTÜ: 13 adayın 13'ü de KESİN; L = D1–D9 ∪
                        C1–C5 ∪ RB1–RB5 ∪ U1–U3 (aşağıda "Dilim 5")
kural değil           zaman sıraları; görevlerin silinmemesi/çapası/dokunulmamışlığı;
                      IMPORT_CONFIRMED/IMPORT_ROLLED_BACK varlığı (oyun satırı batch'e
                      bağlanamaz, şema 7 öncesi yok); tamamlanma hedefinin hâlâ
                      tamamlanmış olması; is_processed; document_before ↔ bugünkü metin
```

```text
sınıf            canlı DB'de zarar       kullanım noktası        canlı çözüm   geri yükleme
D1–D5, D8, D9    onay                    onay kapısı (İş 7) VAR  Kaldır VAR    Dilim 7 reddeder
D6, D7           onay, kaldırma          onay kapısı VAR;        YOK           Dilim 7 reddeder
                                         HELD_BY_RECORDS
C2, C3, C4       geri alma yanlış görevi Dilim 6                 YOK (zararsız Dilim 7 reddeder
                 kaldırabilir                                    tutulur)
C1, C5, RB*      kullanım noktası yok    —                       gerek yok     Dilim 7 reddeder
U1–U3            yok (ölçüldü)           —                       —             Dilim 7 reddeder
```

## Dilimler

```text
#  kapsam                                        commit                                                            durum
1  kayıt dosyası sözleşmesi, yazıcı, rotation    feat(diagnostics): keep a bounded diagnostic log in the state directory  TAMAM
2  tipli sınırların kayda bağlanması + tipsiz     feat(diagnostics): record failures at their typed boundaries             TAMAM
   kaçışların ölçümü                             (PLAN'daki taslak mesaj "record typed failures where they are decided"di;
                                                  dilim talimatının mesajı uygulandı)
3  tipsiz kaçan depolama/dizin hataları → tipli  fix(errors): report storage and directory failures safely                 TAMAM
   Türkçe sonuçlar                               (PLAN'daki taslak mesaj "fix(storage): say in words when the database or
                                                  its folders will not answer"dı; dilim talimatının mesajı uygulandı)
4  R12 saat geriye gidince                       efd6f3a fix(backup): keep backups usable after the clock goes backwards  TAMAM
5  R14 aday ölçümü (yalnız test)                 feadbf8 test(import): measure confirmed import lifecycle contradictions  TAMAM
                                                 (PLAN taslağı "test(backup): measure which import lifecycle
                                                  contradictions a restore can carry"dı; kullanıcı talimatınınki uygulandı)
6  R14 geri alma provenance kapısı               e5f73f7 fix(import): refuse to take back tasks an import did not make   TAMAM
7  R14 geri yükleme yaşam döngüsü kapısı         966b546 fix(restore): reject contradictory import lifecycles before      TAMAM
                                                 confirmation (PLAN taslağı "feat(backup): refuse a backup whose
                                                  imports contradict their own records"dı; kullanıcı talimatınınki)
8  R13 hasarlı veritabanı açılmaz                34ef740 fix(startup): refuse to open a damaged database                  TAMAM
                                                 (PLAN taslağı "feat(…)"; kullanıcı talimatınınki) + a588d0e
                                                 test(startup): open a damaged database in the window smoke
```

**Sıranın gerekçesi.** Kayıt altyapısı (1) olmadan sınırlar (2) bağlanamaz;
tipsiz kaçışlar (3) tahminle değil (2)'nin ölçtüğü listeyle tiplenir. R12 (4)
okuyucudaki sahte retleri kaldırdığı için R14 ölçümünden (5) önce gelir; (5)'in
ölçtüğü `L` olmadan (6) ve (7) kural yazamaz; (6) canlı DB'deki tek zarar
noktasını, (7) yeni çelişkinin girişini kapatır. R13 (8) R14'e bağlı değildir:
(6) veya (7) bir ölçüm sonucu yüzünden durursa (8) yine yapılabilir. Kullanıcının
önerdiği sıra (log → sınırlar → R12 → R14 → R13) korunmuştur; yalnız "tipsiz
kaçan hatalar" ayrı dilim olmuş ve R14 İş 7'nin kalıbıyla üçe bölünmüştür.

## İş 10 / Dilim 1'de uygulanan hâli

```text
commonMain/domain/diagnostics/DiagnosticRecord.kt   DiagnosticLevel (3), DiagnosticEvent (21, kod +
                                                    varsayılan seviye), DiagnosticArea (sabit liste),
                                                    ExceptionClassName, DiagnosticPlace,
                                                    DiagnosticRecord, Diagnostics (+ None)
commonMain/domain/diagnostics/DiagnosticLine.kt     diagnosticLineOf (tek satır, ≤ 2 KiB),
                                                    utcMillisText (takvim kütüphanesi yok)
desktopMain/platform/diagnostics/
  DiagnosticLogFileSystem.kt                        dar dosya sistemi arayüzü + Nio uygulaması
                                                    (yalnız beş kanonik ad, NOFOLLOW, 0700/0600)
  DiagnosticLogSink.kt                              tembel açılış, süreç kilidi, yarım satır onarımı,
                                                    rotation, fail-open
  QueuedDiagnostics.kt                              256'lık kuyruk, tek worker, seq, düşürme raporu,
                                                    500 ms'lik kapanış
platform/files/XdgAppPaths(+Resolver).kt            stateDirectory + logsDirectory
                                                    ($XDG_STATE_HOME, yoksa ~/.local/state)
Main.kt                                             QueuedDiagnostics.inDirectory(paths.logsDirectory, …);
                                                    ana pencere ve açılış hata penceresi kapanırken
                                                    close(); record ÇAĞRISI YOK
```

### Gerçek bir satır

```text
{"v":1,"seq":7,"at":"2025-09-15T08:21:04.512Z","level":"ERROR","event":"storage.write_failed","app":"0.1.0","schema":8,"reason":"COULD_NOT_READ","area":"EXPORT","place":"tasks.updatedAt","fromSchema":3,"toSchema":8,"count":12,"exception":"java.lang.IllegalStateException","cause":"java.lang.IllegalArgumentException"}
```

(`DiagnosticLineTest`'in bayt bayt karşılaştırdığı satır; alanları gösterebilmek
için bütün isteğe bağlı alanlar dolu.) Alan sırası sabittir, BOM yoktur, tek `\n`
satırın sonundadır; `schema` derlemenin `SUPPORTED_SOURCE_SCHEMA_VERSION` (8)
değeridir.

### API neyi alamaz

```text
DiagnosticRecord  event · level · reason: Enum<*>? · area · place: BackupPlace? ·
                  fromSchema/toSchema: Int? · count: Long? · failure: Throwable?
                  → String/CharSequence/Path/Uuid/EntityId/ByteArray parametresi YOK
                    (DiagnosticSurfaceTest yansımayla tarar; sentetik üyeler hariç)
failure           yapıcıda ExceptionClassName.of + ofRootCause'a indirilir ve
                  BIRAKILIR; kayıt Throwable ALAN TUTMAZ (testli). Sınıf adı
                  KClass.qualifiedName; kalıp dışı / > 200 / isimsiz → "?";
                  kök neden 16 adım ve döngü korumalı
reason            yalnız enum sabitinin adı
place             yalnız BackupData / BackupEnvelopeV1 serializer descriptor'larının
                  dizi ve alan adları (+ file, data, envelope); başka her şey → alan YOK
count             negatifse yazılmaz
diagnostics kodu  message / localizedMessage / stackTrace / printStackTrace /
                  suppressed OKUMAZ (kaynak taraması)
```

### Kuyruk, düşürme ve kapanış

```text
record()          numbering kilidi altında: seq = sıradaki sayı, at = clock.now(),
                  ArrayBlockingQueue(256).offer — disk I/O YOK, bekleme YOK, throw YOK.
                  Kuyruk doluysa kayıt düşer ve sayılır; seq harcanır (boşluk = düşen)
worker            tek daemon iş parçacığı "pnp-diagnostics"; 50 ms poll; her kayıt
                  bir satır; kodlama ≤ 2 KiB değilse veya yazılamazsa düşürülmüş sayılır
                  (sink kapalıysa sayılmaz)
düşürme raporu    kuyruk BOŞKEN, numbering kilidi altında kuyruk boşluğu yeniden
                  denetlenerek numaralanır → dosyadaki seq sırası HİÇ bozulmaz;
                  diagnostics.records_dropped WARN + count, içerik yok
clock hatası      kayıt düşer, çağıran hiçbir şey görmez
close()           yeni kayıt kabul edilmez → worker kuyruğu boşaltır → join(500 ms);
                  süre dolarsa kalanlar bırakılır, sink kapatılır (takılmış yazma
                  döner), ikinci join(500 ms); kilit ve dosya bırakılır; iki kez
                  çağrılabilir; kapanıştan sonraki kayıt yazılmaz
```

### Sahiplik, kilit ve rotation

```text
yer               $XDG_STATE_HOME/pnp-tracker/logs/ ; ilk SATIRDA oluşur (0700 dizin,
                  0600 dosya); hiç kayıt verilmeyen yazıcı dizin oluşturmaz (testli)
kilit (R15)       logs/pnp-tanilama.lock, FileChannel.tryLock, süreç boyunca tutulur,
                  dosya SİLİNMEZ (farklı inode yarışı yok), NOFOLLOW ile açılır.
                  Alınamazsa o süreç kalıcı olarak yazmaz; aynı süreçte ikinci yazıcı
                  (OverlappingFileLockException) da çekilir. StartupGate kilidi
                  DEĞİŞMEDİ
adlar             pnp-tanilama.jsonl, pnp-tanilama.1…4.jsonl — başka ad, benzer önek,
                  .part, .bak, .0/.5, kullanıcı dosyası rotation'a GİRMEZ (testli)
sınır             satır eklenince dosya 1 MiB'ı aşacaksa önce rotation; tam 1 MiB'a
                  izin var (512 × 2 KiB), 1 bayt eksik dosyaya 64 baytlık satır sığmaz
rotation          beş adın herhangi biri link/dizin/FIFO ise HİÇBİR ŞEY silinmez, yazma
                  kalıcı durur; .3→.4 ancak gerekirse .4 silinerek, .2→.3, .1→.2,
                  etkin→.1, yeni etkin CREATE_NEW. Taşıma üzerine yazmaz. Sıra yalnız
                  adlardan; mtime değişikliği sonucu değiştirmez (testli). Çökmenin
                  bıraktığı boşluk doldurulur, en eski gereksiz silinmez; iki ayrı
                  evde aynı çökme durumu aynı sonucu verir (testli)
yarım satır       etkin dosya \n ile bitmiyorsa önce \n; o bayt dosyayı 1 MiB'ın
                  üzerine çıkaracaksa dosya olduğu gibi rotate edilir; önceki satırlar
                  ve sonraki satır ayrı ayrı ayrıştırılır (testli)
hata              her dosya sistemi işlemi (hazırlık, kilit, tür, açma, ekleme, silme,
                  taşıma) ve alttaki bozuk kod: çağırana exception YOK; üç ardışık
                  başarısızlık → süreç boyunca kapalı; geçici hata temizlenince sonraki
                  satır yazılır; dosya sayısı ≤ 5, her dosya ≤ 1 MiB, bozuk satır yok
                  (yedi işlemin her biri FaultyLogFileSystem ile testli)
```

### İki gerçek süreç

```text
tutan + ikinci     tutan süreç ilk satırı yazar (HELD); ikinci süreç 500 kayıt verir →
                   dosyada YALNIZ tutanın satırı; tutan bitirince üçüncü süreç 500 satır
                   yazar → 201 + 500 satır, sırayla, hepsi ayrıştırılır
eşzamanlı patlama  iki süreç 3.000'er kayıt → her satır ayrıştırılır; her sürecin
                   satırları tek kesintisiz blok, seq 1, 2, 3 …
SIGKILL            tutan süreç öldürülür → OS kilidi bırakır → test sürecinin sink'i
                   yazar; öldürülenin satırı ve yenisi ayrıştırılır; kilit dosyası durur
```

*(Tarihsel: "201 + 500" ve "3.000'er" satır beklentileri kapanışın 500 ms'sinde o
kadar satırın diske inmesine güveniyordu ve yavaş CPU'da düşüyordu. Stabilizasyon
turunda olay tabanlı protokole çevrildi; bugünkü hâli aşağıda "Stabilizasyon
turu"ndadır.)*

### Testler

```text
commonTest/…/DiagnosticLineTest                 11  bayt bayt satırlar, 21 olay, 3 seviye,
                                                    sınıf adları + kullanıcı içeriği sızmaz,
                                                    döngülü/derin neden, isimsiz sınıf "?",
                                                    yer sözlüğü, negatif sayı, UTC takvimi,
                                                    gerçek JSON okuyucusu
desktopTest/…/DiagnosticLogSinkTest             17  yukarıdaki sahiplik/rotation/hata tablosu,
                                                    2 KiB (2048 kabul, 2049 ret), tam 1 MiB
QueuedDiagnosticsTest                            8  sıra, 8 iş parçacığı × 2.000 (rotation
                                                    altında, seq 1…16.000 kesintisiz), dolu
                                                    kuyruk bloklamaz + düşürme raporu, kapanış
                                                    flush + kilit, takılmış disk ~1 s içinde
                                                    kapanır ve worker ölür, hatalı disk döngü
                                                    kurmaz, bozuk saat, kullanılmayan yazıcı
                                                    dizin açmaz; her testten sonra worker yok
                                                    (stabilizasyon turunda olay tabanlı hâle
                                                    getirildi; "Stabilizasyon turu")
DiagnosticSurfaceTest                            4  API tipleri, kayıt Throwable tutmaz,
                                                    message/stack taraması, üretimde yalnız
                                                    Main kullanır ve record ÇAĞIRMAZ
DiagnosticLogProcessTest                         3  iki gerçek süreç (yukarıda)
XdgAppPathsResolverTest                     14 (+1) state açık/varsayılan/boş/göreli
LogHome (test desteği)                              her testte gerçek data/config/backups/state
                                                    konumlarının var/boyut/mtime/girdi sayısı
                                                    öncesi-sonrası AYNI
```

Tam koşu 3579 / 0 / 0 / 0 (251 sınıf; +44 test, +5 sınıf). İlk tam koşu
öldürülmeden önce gerçek bir hata gösterdi: `AppDirectoryInitializerTest`'in
ortam haritasında `XDG_STATE_HOME` yoktu, çözücü ev dizinine düştü ve testin
"ev dizini okunmaz" şartı 6 testi düşürdü. Haritaya geçici `state` eklendi ve
state dizininin açılışta oluşmadığı iddiası kondu; beklenti gevşetilmedi.

Mutasyonla doğrulandı: boyut sınırı `>` yerine `>=` yapılıp yabancı nesne denetimi
kaldırılınca `DiagnosticLogSinkTest`'in 5 testi düşüyor.

### Dilim 1'in bilerek YAPMADIKLARI

```text
hata sınırlarını kayda bağlamak                 Dilim 2 (Main dahil hiçbir yer record çağırmaz)
beklenmeyen hata handler'ı                      Dilim 2
yeni kullanıcı metni / ayar / log ekranı        YOK
StartupGate kilidinin ömrü                      DEĞİŞMEDİ (R15)
fsync, sıkıştırma, ağ                           YOK
R12, R13, R14                                   Dilim 4–8
```

## İş 10 / Dilim 2'de uygulanan hâli

Tipli hata sınırları kayda bağlandı. Kullanıcıya gösterilen hiçbir metin, hiçbir
tipli sonuç ve hiçbir ekran durumu değişmedi; Room şeması, migration zinciri,
bağımlılıklar ve PLAN değişmedi.

```text
commonMain/domain/diagnostics/RecordSafely.kt   recordSafely (kayıt hatası çağırana ULAŞMAZ),
                                                storageWriteFailed / storageReadFailed /
                                                readShownAsFailed yapıcıları
desktopMain/platform/diagnostics/
  ApplicationFailureRecords.kt                  startupRefusalRecord (ANOTHER_COPY → WARN),
                                                recordingExceptionHandler +
                                                RecordingWindowExceptionHandlerFactory
Main.kt                                         tek yazıcı her sınıra VERİLİR; açılış reddini
                                                kaydeder; iki pencere de kayıt yapan
                                                handler'ın arkasında; kapanışta close()
```

### Kayıt sahipliği — hangi olayı kim yazar

Kural: hatayı **tipli sonuca çeviren** katman kaydeder, bir kez; o sonucu
gösteren controller aynı hatayı ikinci kez yazmaz. İki olayın iki sahibi vardır
ve ikisi de birbirinin hatasını göremez. `DiagnosticOwnershipTest` bu tabloyu
kaynak taramasıyla çiviler; her matris testi de "tam bir kayıt" iddia eder.

```text
olay                               sahip (üretim dosyası)                seviye/alanlar
startup.refused                    ApplicationFailureRecords + Main      ERROR (ANOTHER_COPY → WARN); reason, exception
startup.migration_completed        StartupGate                           INFO; fromSchema, toSchema
settings.read_problem              DesktopSettingsStore                  WARN; reason; SÜREÇ BAŞINA BİR KEZ
settings.write_failed              DesktopSettingsStore                  WARN; reason, exception, cause
storage.read_failed                TaskExportStore(EXPORT), BackupController(BACKUP),
                                   UnfinishedImportsStore, ImportRollbackStore(SETTLED_IMPORTS),
                                   TaskProgressStore, HistoryController, PoolController
storage.write_failed               CellTextStore, TaskFromTextStore, TaskEditStore, TaskSetupStore,
                                   GameSetupStore, ColorCatalogueStore, TaskProgressStore,
                                   ImportReviewStore, ImportConfirmationStore, ImportRollbackStore,
                                   ImportDraftRemovalStore                ERROR; area + reason + sınıf adları
import.file_unreadable             ImportController                      WARN; yalnız NOT_READABLE, DAMAGED_FILE,
                                                                         ENCRYPTED, FILE_CHANGED_WHILE_READING,
                                                                         REJECTED_BY_SAFETY_LIMIT
import.snapshot_failed             VerifiedSnapshotTaker                  ERROR; reason + place (okuyucu reddiyse)
import.changed_meanwhile           ImportConfirmationStore                WARN
import.records_contradict          ImportConfirmationStore (iki kapı da)  WARN; tek kayıt
import.rollback_provenance_broken  ImportRollbackStore (önizleme ve işlem) WARN
import.draft_held_by_records       ImportDraftRemovalStore                WARN
backup.write_failed                DesktopBackupFileGateway (disk),       WARN; reason, exception, cause
                                   BackupController (belge kurulamadı)
backup.rotation_incomplete         SettingsDrivenHousekeeping             WARN; area = yedek türü, count
restore.file_refused               RestoreController                      WARN; reason + place
restore.not_completed              RestoreController (güvenlik yedeği),   ERROR; reason + sınıf adları
                                   LiveBackupRestorer (transaction)
restore.completed                  LiveBackupRestorer                     INFO
export.write_failed                DesktopExportFileGateway               WARN; reason, exception, cause
export.broken_data                 TaskExportStore                        ERROR; reason = ExportInvariant
app.unexpected_failure             RecordingWindowExceptionHandlerFactory ERROR; area, sınıf adları
                                   (pencere), readShownAsFailed (okuma)
diagnostics.records_dropped        QueuedDiagnostics (Dilim 1)            WARN; count
```

**Kaydedilmeyenler (ölçüldü):** kullanıcının iptali, yanlış dosya türü, eski
`.xls`, CSV dilbilgisi hataları, geçersiz renk kodu gibi programlama/doğrulama
hataları, `ALREADY_REMOVED` / `NOT_A_DRAFT`, olağan açılış ve her başarılı
işlem. Uçtan uca başarılı bir tur (oyun, hücre, CSV içe aktarma + onay, dışa
aktarma, geri alma, yedek, ayar) **hiçbir satır yazmaz ve state dizini bile
oluşmaz** (`DiagnosticsSmokeTest`).

### Kaydın maliyeti ve güvenliği

```text
kayıt hatası      recordSafely her kaydı kendi try/catch'inde kurar ve verir; atan bir
                  Diagnostics ile tipli sonuç AYNI (her matris testinde ölçülür)
transaction       kayıt her zaman transaction bittikten SONRA verilir; kaydederken
                  veritabanı yeni bir yazma transaction'ını hemen açabiliyor (ProbingDiagnostics)
SQL               0 ek ifade; §29 ifade haritaları değişmedi
eşzamanlılık      8 eşzamanlı aynı hata → 8 tam satır, seq 1…8, karışma yok
beklenmeyen hata  Compose 1.11.1'in varsayılanı ÖLÇÜLDÜ (bytecode): EDT'ye hata
                  diyaloğu + WINDOW_CLOSING + istisnayı yeniden fırlatma. Sargı önce
                  kaydeder, sonra AYNI istisnayı aynı handler'a verir; kapanış yolu
                  diagnostics.close()'a girdiği için satır en fazla 500 ms içinde diske iner
```

### Tipsiz kaçan hatalar — ölçüm matrisi  *(Dilim 3'ün TAM kapsamı — HARCANDI)*

`UntypedFailureMeasurementTest` (gerçek DB + gerçek hata enjeksiyonu). Davranış
Dilim 2'de DEĞİŞTİRİLMEDİ; ölçülmeyen hiçbir vaka Dilim 3'e giremedi. **Beşinin
beşi de Dilim 3'te tiplendi** (aşağıda); ölçüm testi o dilimde, yerini alan
davranış testleriyle birlikte kaldırıldı.

```text
sınıf / işlem                         çıkan exception              hangi katmandan   bugün kullanıcıya  Dilim 3'te
ImportDraftStore.save (taslak kaydı)  androidx.sqlite.SQLiteException  store → controller  ekran "kaydediliyor"da kalır  ImportFailure (tipli) +
                                                                                                                        storage.write_failed
GameTableStore.observeTable           androidx.sqlite.SQLiteException  store → akış        tipli "okunamadı" YOK         tipli okuma hatası +
ColorCatalogueStore.observeColors     androidx.sqlite.SQLiteException  store → akış        tipli "okunamadı" YOK         storage.read_failed
PoolController.observePool            (her Throwable)                  controller catch    Failed; kusuru de maskeler    catch SQLiteException'a
                                                                                                                        daraltılır (kayıt hazır)
AppDirectoryInitializer.ensureDirectories  java.io.IOException          Main, pencereden    ham exception; mesajda       StartupProblem + Türkçe
                                                                        ÖNCE               MUTLAK YOL var               açılış ekranı
```

Son satır neden bugün kaydedilmiyor: hata, yazıcı kurulmadan önce yükselir ve
mesajı mutlak yol taşır (ölçüldü); Dilim 3 onu tipli bir açılış reddine çevirince
`startup.refused` ile kaydedilecek.

### Testler

```text
desktopTest/…/StorageFailureRecordsTest        10  gerçek SQLite reddiyle on sınır: hücre metni,
                                                   görevi metne dönüştürme, oyun, renk, ilerleme
                                                   (yazma + okuma), inceleme, taslak kaldırma,
                                                   devam eden içe aktarmalar, dışa aktarma okuması;
                                                   her birinde tipli sonuç + TEK kayıt + atan log
                                                   ile aynı sonuç
ImportFailureRecordsTest                       10  snapshot'ın üç sebebi (yazılamadı / geri okunamadı +
                                                   place / DB okunamadı), arada değişen veri, iki
                                                   kapının çelişkisi, geri almanın provenance'ı
                                                   (önizleme + işlem), kayıtların tuttuğu taslak,
                                                   okunamayan dosya; iptal ve kullanıcı hatası 0 kayıt
BackupFailureRecordsTest                        7  yedek dosyası yazılamadı, DB okunamadı, belge
                                                   kurulamadı, rotation eksik kaldı (tür + count),
                                                   bozuk ayar dosyası (SÜREÇTE BİR KEZ), ayar
                                                   yazılamadı, CSV yazılamadı, bozuk renk yuvası
RestoreRecordsTest                              5  dosya reddi (+place) ve dosyasız diyalog, güvenlik
                                                   yedeği okunamadı/yazılamadı, canlı replace geçti
                                                   (INFO), arada değişen veri, transaction ortasında
                                                   depolama reddi
StartupRecordsTest                              5  gerçek v3 → v8 göçü (INFO + fromSchema/toSchema),
                                                   olağan açılış 0 kayıt, şema 9, veritabanı olmayan
                                                   dosya, ikinci kopya → WARN
UnexpectedFailureRecordsTest                    5  geçmiş ve havuz okumasında depolama ≠ kusur ayrımı,
                                                   pencereye ulaşan hata kaydedilip AYNI handler'a AYNI
                                                   istisnayla veriliyor, atan log bunu değiştirmiyor
RecordingCostTest                               3  kayıt transaction bittikten SONRA (ProbingDiagnostics),
                                                   8 eşzamanlı hata → 8 tam satır (seq 1…8), kusur
                                                   kaydedilmiyor ve maskelenmiyor
UntypedFailureMeasurementTest                   3  Dilim 3'ün kapsamı: taslak kaydı, oyun tablosu +
                                                   renk kataloğu okuması, açılış dizini (IOException,
                                                   mesajında mutlak yol)
DiagnosticOwnershipTest                         4  olay → sahip tablosu, depolama sahipleri, recordSafely
                                                   dışında record() yok, Main tek yazıcıyı kurup dağıtıyor
DiagnosticsSmokeTest                            3  üç geçici XDG: başarılı tur 0 satır ve state dizini
                                                   YOK; iki gerçek hata → iki satır, yalnız geçici state
                                                   altında; geri yükleme INFO satırı
DiagnosticSurfaceTest                       4 (±0) Dilim 1'in "hiç kayıt yok" testi, yazıcıyı tutan
                                                   üretim dosyalarının kapalı listesine ÇEVRİLDİ
```

### Dilim 2'nin bilerek YAPMADIKLARI

```text
tipsiz kaçan hataları tiplemek                   Dilim 3 (yukarıdaki matris)
yeni Türkçe metin / yeni tipli sonuç             YOK
çökme politikasını değiştirmek                   YOK (ölçüldü ve korundu)
başarı kaydı                                     YOK (yalnız iki INFO)
R12, R13, R14                                    Dilim 4–8
```

## İş 10 / Dilim 3'te uygulanan hâli

Dilim 2'nin **ölçtüğü** beş tipsiz kaçış tiplendi; başka hiçbir yol açılmadı ve
yeni bir olay kodu eklenmedi. Room şeması, migration zinciri, fixture, PLAN ve
bağımlılıklar değişmedi.

```text
commonMain/domain/diagnostics/ObservedReadings.kt   answeringStorageRefusal: gözlenen bir okumanın
                                                    SQLiteException'ını tipli cevaba çevirir, TEK
                                                    kayıt verir, başka her şeyi AYNEN yükseltir
domain/importprep/ImportFailure.kt                  + COULD_NOT_SAVE (yapıcı DEĞİŞMEDİ: dört üretim
                                                    yeri initCause çağırıyor, ikinci argüman onları kırardı)
data/repository/ImportDraftStore.kt                 saveDraftBatch'in SQLiteException'ı → tipli sonuç +
                                                    storage.write_failed (area IMPORT_DRAFT)
ui/feature/importreview/ImportScreenState.kt         + NotSaved(session)
ui/feature/importreview/ImportController.kt          COULD_NOT_SAVE oturumu KORUYARAK NotSaved'e düşer
ui/feature/importreview/ImportScreen.kt              NotSaved = önizlemenin kendisi + üstte tek cümle
ui/feature/games/GameTableScreenState.kt             + GameTableRowsState.Failed
ui/feature/games/GameTableController.kt              readAttempt/readAgain; iki akış da seamden geçer;
                                                     redrawn() reddedilmiş okumayı boş tabloya çevirmez
ui/feature/games/GameTableScreen.kt                  UnreadableTable + LaunchedEffect(readAttempt)
ui/feature/colors/ColorCatalogueState.kt             + ColorCatalogueState.Failed
ui/feature/colors/ColorCatalogueController.kt        readAttempt/readAgain; seam
ui/feature/colors/ColorCatalogueScreen.kt            UnreadableCatalogue + LaunchedEffect(readAttempt)
ui/feature/pools/PoolController.kt                   geniş catch KALKTI; seam; readAttempt/readAgain;
                                                     nullable snapshot dansı da kalktı
ui/feature/pools/PoolScreen.kt                       UnreadablePool + LaunchedEffect(readAttempt)
domain/backup/automatic/MigrationSnapshotSet.kt      + StartupProblem.FOLDERS_NOT_CREATED
platform/files/AppDirectoryInitializer.kt            IOException → StartupRefused(FOLDERS_NOT_CREATED);
                                                     mesajdaki MUTLAK YOL kaldırıldı
ui/feature/startup/StartupErrorScreen.kt             dokuzuncu Türkçe cümle (exhaustive when)
Main.kt                                              sıra: yollar → tanılama yazıcısı → dizinler → kapı;
                                                     dizin hatası da aynı catch'ten, aynı kayıttan geçer
```

### Beş yolun önceki ve yeni davranışı

```text
yol                          önce                                yeni
ImportDraftStore.save        ham SQLiteException controller'dan   ImportFailure.COULD_NOT_SAVE (reddin
                                                                  kendisi store'da kalır; sınıfları zaten
                                                                  kayıtta, mesajı SQL);
                             çıkıyor, ekran "kaydediliyor"da      NotSaved(session): önizleme, dosya adı
                             kalıyordu                            ve taslak duruyor, düğme geri geliyor,
                                                                  aynı taslak yeniden kaydedilebiliyor
GameTableStore.observeTable  ham SQLiteException akıştan çıkıyor  GameTableRowsState.Failed + "Yeniden dene";
                             (tipli "okunamadı" YOK)              görünüm/süzgeç değişimi boş tablo çizmez
ColorCatalogueStore          ham SQLiteException akıştan çıkıyor  ColorCatalogueState.Failed + "Yeniden dene";
  .observeColors                                                  tablo ve havuzun editör listesi ELİNDEKİ
                                                                  renkleri korur (boş listeye düşmez)
PoolController.observePool   catch her Throwable'ı Failed yapıyor yalnız SQLiteException Failed olur;
                             (kusuru da maskeliyordu)             kusur ve cancellation AYNEN yükselir;
                                                                  "Yeniden dene" eklendi
AppDirectoryInitializer      pencereden ÖNCE ham java.io.         StartupRefused(FOLDERS_NOT_CREATED);
  .ensureDirectories         IOException; mesajında MUTLAK YOL    açılış hata ekranı + tek startup.refused;
                                                                  DB ve kapı hiç açılmaz; yol silindi
```

### Kullanıcının gördüğü Türkçe cümleler

```text
import_error_could_not_save  İçe aktarma kaydedilemedi. Dosyadan okunanlar olduğu gibi duruyor ve
                             hiçbir şey yarım kaydedilmedi. Biraz sonra yeniden kaydetmeyi deneyin.
table_unreadable             Oyun tablosu okunamadı.
colors_unreadable            Renk kataloğu okunamadı.
pool_error                   Havuz okunamadı.        (eskiden "…Uygulamayı yeniden başlatmayı deneyin."
                                                      diyordu; artık ekranda düğme var)
reading_unreadable_hint      Verileriniz olduğu gibi duruyor; hiçbir şey değiştirilmedi.
                             Biraz sonra yeniden deneyebilirsiniz.
reading_read_again           Yeniden dene
startup_folders_not_created  PNP kendi klasörlerini oluşturamadı, bu yüzden veri dosyanız hiç açılmadı.
                             Disk dolu olabilir ya da klasörlerin izinleri değişmiş olabilir;
                             kontrol edip PNP'yi yeniden başlatın.
```

Üç ekran ortak bir hata modelini paylaşıyor (`Strings.Reading`: ipucu + düğme)
ama **ne olduğunu her ekran kendi sözcükleriyle söylüyor**; genel bir "bir
şeyler yanlış gitti" durumu yok.

### Kayıt sahipliği — bu dilimde eklenenler

```text
olay                  sahip                       alanlar
storage.write_failed  ImportDraftStore            area=IMPORT_DRAFT, reason=COULD_NOT_SAVE, sınıf adları
storage.read_failed   GameTableController         area=GAME_TABLE
storage.read_failed   ColorCatalogueController    area=COLORS
storage.read_failed   GameTableController (katalog) / PoolController (katalog)   area=COLORS
storage.read_failed   PoolController              area=POOLS
startup.refused       ApplicationFailureRecords + Main   reason=FOLDERS_NOT_CREATED, sınıf adları
```

`readShownAsFailed` artık yalnız `HistoryController`'ın; havuz onu bırakıp
tipe göre daraltan seam'e geçti. Yeni olay kodu **yok**.

### Toparlanma ve log fırtınası

```text
akış biter        bir ret akışı BİTİRİR; okunmamış bir okumadan gelecek başka satır yoktur
tek satır         ekran ne kadar açık kalırsa kalsın bir ret bir satır yazar
yeniden deneme    readAttempt LaunchedEffect'in anahtarıdır; düğme onu artırır, eski toplama
                  biter, yenisi başlar
toparlanma        gelen ilk başarılı okuma Failed'ı temizler (tablo, katalog ve havuz için testli)
editör listesi    tablo/havuz kataloğu reddedilirse ELİNDEKİ renkler kalır; boş listeye düşmez
```

### Programlama hatası ve cancellation

```text
SQLiteException        tipli sonuç + TEK kayıt
IllegalStateException  AYNEN yükselir, kaydedilmez  (üç akışta da testli)
IllegalArgumentException  AYNEN yükselir, Failed OLMAZ, kaydedilmez (üç akış + seam'in
                       kendisi; stabilizasyon turunda ayrı testle çivilendi)
NullPointerException   aynı (katalog akışında testli)
Error                  yakalanmaz, dönüştürülmez, kaydedilmez: testin kendi Error alt
                       türü AYNI nesne olarak yükselir, üç ekran Loading'de kalır ve
                       seam'in `refused` cevabı hiç çağrılmaz (stabilizasyon turu)
CancellationException  AYNEN yükselir, kaydedilmez — ekranı kapatmak hata değildir
```

Yapısal kanıt `ReadingRefusalSurfaceTest`: üç controller'da `.catch {` yok,
`catch (… : Throwable|Exception)` yok, üçü de seam'den geçiyor ve seam tipe göre
daraltıp geri fırlatıyor.

### Testler

```text
desktopTest/…/DraftSaveRefusalTest              5  gerçek DB + gerçek SQLite reddi + gerçek
                                                   ImportController: NotSaved oturumu KORUYOR,
                                                   isBusy false, aynı taslak yeniden kaydediliyor;
                                                   0 batch / 0 raw block kalıyor; TEK kayıt ve
                                                   controller ikinci kez yazmıyor; atan log sonucu
                                                   değiştirmiyor; başarılı kayıt 0 satır
UnreadableReadingsTest                         12  üç okuma: tipli Failed + TEK güvenli kayıt;
                                                   görünüm/süzgeç değişimi boş tablo çizmiyor;
                                                   üçü de sonraki başarılı okumayla toparlanıyor ve
                                                   ikinci kayıt YAZMIYOR; tablonun editör renkleri
                                                   elde kalıyor; IllegalState/NullPointer ve
                                                   CancellationException AYNEN yükseliyor ve
                                                   kaydedilmiyor; atan log üç cevabı da değiştirmiyor
StartupFolderRefusalTest                        5  yazılamaz dizin → StartupRefused
                                                   (FOLDERS_NOT_CREATED); DB/ayar/yedek dosyası
                                                   OLUŞMUYOR; uygulamanın yazdığı iki cümlede yol yok
                                                   (dosya sisteminin kendi nedeni yalnız SINIF adı için
                                                   tutuluyor); kayıt tek güvenli satır; state de
                                                   yazılamazken ikinci hata yok ve hiçbir şey
                                                   oluşmuyor; olağan açılış 0 satır
ReadingRefusalSurfaceTest                       3  yapısal: havuzda ve üç controller'da `.catch {` ve
                                                   geniş catch YOK, üçü de seam'den geçiyor, seam tipe
                                                   göre daraltıp geri fırlatıyor
desktopTest/…/ui/UnreadableScreensTest          4  gerçek Compose sahnesi, 1100×720 1× ve 640×460 2×
                                                   metin ×1,3: üç ekran da kendi cümlesini ve
                                                   `Yeniden dene`yi gösteriyor, "boş" cümlelerini
                                                   GÖSTERMİYOR, sınıf adı/SQL/yol/enum sızmıyor;
                                                   okunabilen katalog "okunamadı" demiyor
UntypedFailureMeasurementTest                  -3  Dilim 2'nin ölçüm testi KALDIRILDI: ölçtüğü üç
                                                   vakanın üçü de artık tipli ve yukarıdaki davranış
                                                   testleriyle çivili (kendi belgesi bu turu şart
                                                   koşuyordu)
UnexpectedFailureRecordsTest                4 (±0) "havuz kusuru app.unexpected_failure yazar" testi
                                                   bilerek ÇEVRİLDİ: kusur artık AYNEN yükseliyor,
                                                   kaydedilmiyor ve havuz Loading'de kalıyor
AppDirectoryInitializerTest                 6 (±0) davranış aynı; yalnız fırlatılan tip değişti
StartupErrorScreenTest                      6 (±0) dokuzuncu neden taramaya kendiliğinden girdi
ImportDraftStoreTest                        (±0)   "hücre yazılamazsa import geri alınır" testi bilinçli
                                                   ÇEVRİLDİ: artık COULD_NOT_SAVE bekliyor, mesajda SQL/
                                                   tablo/koordinat OLMADIĞINI iddia ediyor; yarım batch
                                                   kalmadığı iddiası aynen duruyor
ImportControllerTest / CsvImportScreenTest  (±0)   mevcut içe aktarma akışı gerilemedi
```

### Dilim 3'ün bilerek YAPMADIKLARI

```text
yeni olay kodu                                   YOK (liste kapalı, PLAN 14.7.2)
yeni ekran                                       YOK (üç mevcut ekran birer durum kazandı)
otomatik yeniden deneme / geri çekilme döngüsü   YOK — yeniden deneme kullanıcının basışıdır
ölçülmemiş bir yolu tiplemek                     YOK (PoolControllers.observeNavigationSummary,
                                                 ImportReviewStore.observeColors ve HistoryController
                                                 olduğu gibi bırakıldı; ölçüm matrisinde yokturlar)
başarı kaydı / kayıt sayısının artması           YOK
transaction veya atomiklik değişikliği           YOK (taslak kaydı hâlâ tek transaction)
R12, R13, R14                                    Dilim 4–8
```

## Stabilizasyon turu  *(İş 10 / Dilim 3 sonrası — `test(desktop): make process and window checks deterministic`)*

Dilim değil; Dilim 4'ten önce doğrulama altyapısındaki iki kararsızlık kapatıldı
ve iki eksik hata sınırı testi eklendi. **Üretim kodu değişmedi**; Room şeması,
migration, PLAN, fixture ve bağımlılıklar aynı.

### Hız bağımsız hâle getirilen invariant

Eski testler sözleşmeyi değil makineyi ölçüyordu: "close() dönünce 3.000 (ya da
201 + 500, ya da 16.000) satır diskte olmalı". PLAN `14.7.1`'in söylediği ise
kuyruğun kapanışta **en fazla 500 ms** bekleneceğidir; o sürede kaç satırın
yazılacağı donanıma, güç ayarına ve yüke bağlıdır. Geçerli invariant:

```text
1  diske inen her satır BÜTÜN ve tek başına ayrıştırılabilir; dosya \n ile biter
2  bir sürecin satırları seq 1, 2, 3 … BOŞLUKSUZ bir önektir: kapanışın yetişemediği
   kayıtlar SONDAN eksiktir, ortadan asla (ortadaki boşluk = düşürülmüş kayıt)
3  dosyadaki satırlar, yazan sürecin kendi disk çağrılarıyla yazdığını söylediği
   satırların TAM kendisidir (ne eksik ne fazla)
4  kilidi alamayan süreç 0 satır yazar; kilidi tutan varken ikinci süreç yazamaz
5  close() tanımlı üst sınırda döner (iki × 500 ms; test 1.500 ms ile bakar),
   işçi iş parçacığı biter, dosya ve kilit bırakılır; sonraki süreç/sink aynı
   kilidi alıp yazar
6  süre dolduğu için bırakılan kayıtlar DÜŞÜRME SAYACINA GİRMEZ ve
   diagnostics.records_dropped yazılmaz: rapor "yer açıldığında" yazılır, kapanışta
   o an yoktur; kayıp dosyanın SONUNDAKİ eksiktir (PLAN 14.7.1'in 500 ms ve süreç
   öldürme hükümleriyle aynı sınıf). Bu, ölçülmüş mevcut davranıştır; değişmedi
7  normal (hızlı disk) kapanışta kuyrukta bekleyen küçük ve belirli bir küme
   eksiksiz yazılır
```

### 3.000 satır varsayımının yerine geçen mekanizma

```text
FaultyLogFileSystem       awaitLines(n) — sonraki n satır diske inene kadar bekler
(test çifti, üretimde     awaitLineWith(metin) — o metni taşıyan satıra kadar bekler
 kanca YOK)               appendsBeforeGate + appendGate — ilk k yazma geçer, (k+1).
                            yazma kapıda TUTULUR; awaitHeldAtGate() işçinin orada
                            olduğunu bildirir
                          awaitFirstLineOrRefusal() — ilk satır diskte (true) ya da
                            kilit başka süreçte (false); linesWritten sayacı
                          bekleme sınırları (300 sn) ölçüm DEĞİL: hiç gelmeyen olay
                            testi asmak yerine düşürsün diye
QueuedDiagnosticsTest     sıra / 8 × 2.000 / saat / dolu kuyruk testleri close()'u
                          ancak satırlar diske İNDİKTEN sonra çağırır → kapanışın
                          flush kapasitesi ölçülmez
                          normal kapanış: işçi 1. yazmada kapıda, 9 kayıt kuyrukta;
                          close() ayrı iş parçacığında başlar (join'de TIMED_WAITING
                          görülür), kapı açılır → 10 satır seq 1…10, close ≤ 1.500 ms,
                          işçi yok, sonraki sink kilidi alır
                          süre dolan kapanış: 3 satır geçer, 4. yazma sonsuza kadar
                          tutulur → close ≤ 1.500 ms, işçi biter, dosyada yalnız seq
                          1,2,3 bütün satırlar, records_dropped YOK, dosya \n ile biter,
                          sonraki sink kilidi alır
DiagnosticLogWriterProcess her adımı olduktan SONRA söyler: HELD/REFUSED, WROTE/REFUSED
(ikinci süreç protokolü)   (ilk kayıt diskte ya da kilit reddedildi), stdin'de "go"
                          bekler, sonra patlama + close, en sonda DONE <kendi disk
                          çağrılarıyla yazdığı satır sayısı>
DiagnosticLogProcessTest  iki süreç de CANLIYKEN kilidi dener → tam biri WROTE, öbürü
                          REFUSED (kesin); ikisi aynı anda patlar; reddedilen 0 satır,
                          yazanın DONE sayısı = dosyadaki satırlar = seq 1…n; sonra
                          üçüncü süreç aynı kilidi alıp yazar. Tutan+ikinci testinde
                          beklenen "201 + 500" yerine tutanın ve üçüncünün kendi
                          sayıları (tutanın elle yazdığı ilk satır seq 0, kuyruğu 1'den)
```

Kalan tek zaman bağımlılığı sözleşmenin kendi üst sınırıdır (close ≤ 1.500 ms) ve
normal kapanış testinde kapı açıldıktan sonra 10 küçük satırın bu sınır içinde
yazılmasıdır — sabit ve küçük bir iş, makinenin kapasitesi değil. `RecordingCostTest`
(8), `DiagnosticsSmokeTest` (2) ve `StartupFolderRefusalTest` (1) da kapanışla
küçük sabit kümeler yazar; aynı sınıftadır ve değişmedi.

**Ölçüm (bu turda):** cgroup CPU kotası YALNIZ kendi Gradle sürecine verildi
(`systemd-run --user --scope -p CPUQuota=…`; sistem güç ayarına dokunulmadı).
Dar küme (QueuedDiagnosticsTest 8, DiagnosticLogProcessTest 3, DiagnosticLogSinkTest
17, UnreadableReadingsTest 14, SafeWindowCloserTest 13 = 55 test) normal ×3,
%100, %50 ve %25 kotada 55/55 geçti (%25'te süreç testi 65 sn sürdü). Kontrol:
DEĞİŞİKLİKSİZ HEAD (`62e36a0`, geçici worktree) aynı %50 kotada üç test
düşürdü — "unexpected 0 lines", 201 yerine 200 EXPORT satırı ve 16.000 yerine
8.043 satır. Yani kota eski kusuru yeniden üretiyor, yeni mekanizma üretmiyor.

**Üretimde değişiklik yok:** sözleşmeye aykırı ölçülmüş bir hata bulunmadı. Kod
okumasıyla görülen dar bir kapanış yarışı "Açık sınırlar"a yazıldı.

### Güvenli pencere seçimi

Dilim 3 smoke'unun betiği pencereyi `wmctrl -l | grep -i PNP` ile arıyordu ve
kullanıcının başlığında "PnP" geçen BAŞKA bir programın penceresine bir kez
kapatma isteği gönderdi (pencere kapanmadı). Tarama: repoda pencere aracı kullanan
**hiçbir** betik/kod yoktu; o betik oturumun geçici dizinindeydi ve bu turda
kullanılmadı. Kalıcı yol artık tek yardımcıdır:

```text
desktopTest/…/platform/desktop/DesktopWindows.kt
  SafeWindowCloser(commands, belongsToRun)
    find(title)   wmctrl -l -p → WindowChoice: One / None / Several / Untrustworthy
    close(title)  1 seç   TAM başlık (kısmi, büyük/küçük harf farkı, baştaki boşluk
                          YOK) + _NET_WM_PID başlatılan sürecin AĞACINDA
                          (isInProcessTree: ProcessHandle ebeveyn zinciri)
                  2 hemen önce  yeni listeden AYNI One + xprop -id <id> _NET_WM_PID
                          aynı süreç
                  3 gönder  wmctrl -i -c <id> — yalnız bu
    listeye güvenilmez  bir satır pencere olarak okunmuyorsa (başlıktaki satır sonu
                  sahte satır üretir) ya da bir id iki kez geçiyorsa HİÇBİR ŞEY yapılmaz
    komut         SystemCommands: ProcessBuilder(argv), kabuk YOK; argv yalnız sabitler
                  ve 0x[0-9a-f]{1,8} biçimli doğrulanmış id; başlık hiçbir komuta girmez
    ret mesajı    yalnız id, süreç kimliği ve sayılar — başka programın başlığı YOK
desktopTest/…/platform/desktop/DesktopWindowSmoke.kt  (./gradlew desktopWindowSmoke)
  uygulamayı bu derlemeden DOĞRUDAN kendi süreci olarak başlatır (java -cp … MainKt),
  XDG_DATA/CONFIG/STATE_HOME ve java.io.tmpdir geçici; "PnP Üretim Takipçisi"
  penceresini yukarıdaki yardımcıyla kapatır; çıkış 0, geçici veri dizininde pnp.db,
  -wal/-shm yok, state boş, çıktıda exception yok, gerçek dosyalar aynı, arkada süreç
  yok, kendi geçici dizinini (yalnız tam o yolu) siler. Pencere güvenle seçilemezse
  hiçbir pencereye istek gitmez; uygulama KENDİ süreci olarak durdurulur ve smoke düşer
app/build.gradle.kts  desktopWindowSmoke: JavaExec, desktopTest çıktısı + çalışma
  zamanı sınıf yolu; `check`'e BAĞLI DEĞİL (ekran ister, pencere açar)
```

AWT'nin `_NET_WM_PID`'i gerçekten kendi süreç kimliğiyle yazdığı bu makinede
ölçüldü (kendi kendini kapatan deneme penceresi; başka pencereye eylem yok).

`SafeWindowCloserTest` (13): kısmi başlıklı ilgisiz pencere (o olayın başlığı
biçiminde uydurma bir başlık dâhil; gerçek başlık repoya yazılmadı) ve aynı sürecin başka başlıklı penceresi seçilmez · doğru
süreç + tam başlıklı tek pencere seçilir ve yalnız o kapatılır · aynı başlıklı başka
süreç (kullanıcının açık kopyası) seçilmez · sıfır ve birden fazla aday reddedilir,
hiçbir istek gitmez · seçimle kapatma arasında el değiştiren/kaybolan pencere ve
kendi süreç kimliği tutmayan pencere kapatılmaz · başlıktaki `"; wmctrl -c …`,
`$(…)`, ters tırnak, `&&`, `|`, tırnak komuta dönüşmez, komutlara yalnız sabitler ve
doğrulanmış id girer · satır sonuyla sahte satır üreten başlık (yeni id / gerçek id
tekrarı / okunmayan satır) güvenilmez sayılır · biçimsiz id/pid listeyi okunmaz
yapar · ret mesajı başka programın başlığını taşımaz · süreç ağacı ilişkisi gerçek
ProcessHandle ile · smoke'un aradığı başlık `strings.xml`'deki gerçek başlıktır ·
**kaynak taraması:** repodaki bütün .kt/.kts/.sh/.py/.java/.gradle dosyalarında
wmctrl/xdotool/xprop/xkill/_NET_WM geçen tek dosyalar yardımcının kendisi ve testidir;
yardımcıda tek kapatma isteği vardır ve id iledir (`-F`, `-a`, `-r`, `:ACTIVE:`,
xdotool, kabuk YOK).

**Bu turun gerçek masaüstü smoke'u** (`./gradlew desktopWindowSmoke`, geçici XDG):
uygulama süreç 18492 olarak açıldı; `0x00400007` penceresi (süreç 18492, başlık tam
"PnP Üretim Takipçisi") seçildi, kapatma isteği yalnız ona gitti; çıkış kodu 0;
geçici veri dizini `[backups, pnp-baslangic.lock, pnp.db, pnp.db.lck]` (-wal/-shm
yok); state boş; çıktıda exception 0; arkada süreç 0; geçici dizin silindi.
Kullanıcının dört penceresinin id + süreç listesi öncesi/sonrası birebir aynı;
gerçek pnp.db / .lck / backups / config / ~/.local/state aynı.

### Eklenen hata sınırı testleri

`UnreadableReadingsTest` +2 (12 → 14): üç okuma yolunda (oyun tablosu, renk
kataloğu, havuz) ve ortak seam'in (`answeringStorageRefusal`) kendisinde
`IllegalArgumentException` AYNI nesne olarak yükselir, ekranlar `Loading`'de kalır
(tipli "okunamadı" OLMAZ), seam'in `refused` cevabı çağrılmaz ve kayıt 0; testin
kendi `Error` alt türü (`ReaderBroke`) için de aynısı — yakalanmaz, dönüştürülmez,
kaydedilmez. Mevcut `IllegalStateException`, `NullPointerException` ve
`CancellationException` testleri korundu. Üretimde `Throwable`/`Error` yakalayan
kod eklenmedi.

## İş 10 / Dilim 4–8'de uygulanan hâli

### Dilim 4 — R12: saat geriye gidince  *(`efd6f3a`)*

```text
BackupValues          import_batches'te updatedAt < importedAt ve her satırda updatedAt <
                      createdAt reddi KALDIRILDI; yalnız "geçerli an mı" kaldı
EntityTimestamps      updatedAt >= createdAt ve deletedAt >= createdAt şartları KALDIRILDI;
                      deletedAt == updatedAt KORUNDU
ImportDao             allBatches, batchesWithFingerprint → ORDER BY imported_at DESC, id;
                      draftTasksOfBlock → ORDER BY created_at, name, id (eşitlik id ile çözülür)
AutomaticBackupRotation surplusOf: az önce yazılan set her zaman "en yeni" sayılır, sonra damga,
                      deneme, ad; keep 1..5 için az önce yazılan hiçbir zaman fazlalık değildir
epoch değerleri       aynen taşınır; hiçbir yol düzeltmez
```

Testler: geriye giden anlarla yedek → geri yükleme → yeniden yedek; geriye
giden saatte düzenlenmiş bir görevin geri alınması `TASKS_WERE_EDITED`; bir gün
geriye giden inceleme saatiyle bütün içe aktarma yolculuğu; v7 migration seti
ters satırlarla; rotation keep 1..5 + migration seti. Dar koşu 16 sınıf / 183.

### Dilim 5 — R14 ölçümü  *(`feadbf8`, yalnız test)*

`LifecycleContradictionReachTest` İş 7'nin kalıbıyla: kanonik belge → tek
mutasyon → `backupDocumentOf` → gerçek `UntrustedBackupReader` +
`TemporaryBackupProbe` → `LiveBackupRestorer` → canlı DB eşitliği + sağlamlık.

```text
aday     canlı DB'ye ulaşır   canlı DB'de bozulan    Dilim 6'dan önce geri alma
C1       evet                 C1                     PROVENANCE_BROKEN (zaten)
C2       evet                 C2                     zararsız
C3       evet                 C3                     ZARAR: içe aktarmanın yapmadığı görev tombstone'landı
C4       evet                 C4                     ZARAR: içe aktarmanın yazmadığı hücre geri yüklendi
C5       evet                 C5                     zararsız
RB1      evet                 RB1 + RB2              (geri alınmış; geri alma yok)
RB2–RB5  evet                 yalnız kendisi         (geri alınmış)
U1–U3    evet                 yalnız kendisi         zararsız
kendi yollar  1 ve 42 görevde onay, geri alma, taslak → hiçbir aday bozulmaz
eski şemalar  3–7 migrate edilince hiçbir aday sayılmaz
```

Sonuç: `L` = D1–D9 ∪ 13 aday; PLAN'ın bağlayıcı kararı uygulanabilir kaldı.

### Dilim 6 — geri alma kapısı  *(`e5f73f7`)*

`planImportRollback`, `NO_CELL_SNAPSHOT` denetiminden sonra C2 (sayılan görev
= taslak sayısı), C3 (üretilen görevin kaynağı taslağın bloğu) ve C4 (boş hedef
yok, hedefler = kayıtlı hücreler) bozuksa `PROVENANCE_BROKEN` döner.
`ImportDao.rollbackFactsOf` bu olguları var olan okumalardan doldurur: **0 ek
SQL** (`ImportRollbackQueryCountTest` aynı). Ölçüm testi artık C1–C4 için
önizleme ve geri almada `PROVENANCE_BROKEN`, C5 ve U1–U3 için zararsız geri alma
bekler. Dar koşu 11 sınıf / 101.

### Dilim 7 — geri yükleme kapısı  *(`966b546`)*

```text
tek tanım             domain/importhealth/ImportLifecycle.kt: draftContradictionsOf(DraftRecords)
                      D1–D9'un TEK tanımı — veritabanı sınıflandırıcısı (draftHealthOf) ve
                      bellek içi kapı ikisi de onu çağırır; LifecycleContradiction C1…U3;
                      importRecordsHealthIn(BackupData) her batch için {draft, lifecycle}
                      (CONFIRMED + boş cells → C iddiası yok; U1–U3 yalnız DRAFT dışı)
kapı yeri             RestoreController.chooseBackup, okuyucu Valid dedikten SONRA, Confirming'den
                      ÖNCE; okuyucu SIKILAŞTIRILMADI (içe aktarma snapshot'ı ve migration seti aynı
                      okuyucudan geçer)
sonuç                 BackupRejection(IMPORT_RECORDS_CONTRADICT, place "importBatches") →
                      Rejected; restore.file_refused tek satır (seçim başına bir kez)
yan etki              yok: exporter okuması 0, güvenlik yedeği 0, restorer 0; canlı DB ve yedek
                      klasörü aynı (RestoreSmokeTest ile gerçek bileşenlerde de)
Türkçe cümle          "Bu yedekteki bir içe aktarmanın kayıtları birbiriyle uyuşmuyor, bu yüzden
                      geri yüklenemez. Verileriniz olduğu gibi duruyor ve hiçbir şey yazılmadı;
                      başka bir yedek seçebilirsiniz." — kod, tablo, UUID, yol yok
kusur                 kapı hiçbir exception yakalamaz; kusur aynen yükselir
```

Fixture ayrımı: `aWholeBackup` ve `fillWithEverything` biçim/tam kapsam
fixture'ları olarak **değişmedi** (ölçülen çelişkileri: `fillWithEverything`
DRAFT [D3], CONFIRMED [C1, C4, C5, U1], ROLLED_BACK [RB1, RB2, U3];
`aWholeBackup` [C2, C5]). Gerçekten geri yükleme kabulü gereken akış testleri
tutarlı türevleri kullanır: `aRestorableBackup()` (kendi içinde sağlam olduğunu
denetler) ve `fillWithEverythingARestoreAccepts` — içe aktarma kayıtlarını
**silmez**, tutarlı hâle getirir, her tablo dolu kalır; smoke A durumunun
`importRecordsHealthIn` ile sağlam olduğunu ayrıca doğrular. Dar koşu 21 sınıf /
167 + RestoreSmokeTest 2/2.

### Dilim 8 — R13: hasarlı veritabanı açılmaz  *(`34ef740`, smoke `a588d0e`)*

Önce ölçüldü (`DatabaseDamageMeasurementTest`, tam koşudaki değerler; eşik değil
kayıt, makineye bağlı):

```text
görev    page_count  page_size  bayt        quick_check  integrity_check  foreign_key_check
1.203    350         4096       1.433.600   1,5 ms       5,0 ms           1,3 ms
12.030   2.869       4096       11.751.424  12,2 ms      72,8 ms          18,0 ms

hasar (geçici kopyada)              user_version  quick_check          integrity_check   foreign_key_check
tablo yaprak sayfası çöp            8             BULDU (SQLiteExc.)   BULDU             BULDU (exception)
indeks yaprak sayfası çöp           8             BULDU (2 satır)      BULDU             KAÇIRDI
dosya yarıdan kesik                 OKUNAMADI     BULDU (SQLiteExc.)   BULDU             BULDU (exception)
başlık freelist'i kullanımdaki      8             BULDU (6 satır)      BULDU             KAÇIRDI
  bir sayfayı gösteriyor
```

`quick_check` dört sınıfın dördünü buldu; dilim durmadı. Uygulamanın
veritabanları `auto_vacuum = FULL`'dur (ölçüldü): freelist her commit'te boşalır,
bozulabilecek bir trunk sayfası kalmaz; bu yüzden freelist sınıfı başlığın
freelist işaretçisinin kullanımdaki bir sayfayı göstermesi olarak kuruldu.
Kesik dosyada `user_version` okunamadığı için PLAN sırasıyla önce
`DATABASE_NOT_READABLE` gelir; o da Room'dan önce reddedilir.

```text
kapı                  StartupGate: user_version okunduktan hemen sonra, sürüm 1..8 için
                      ConsistentDatabaseClone.passesQuickCheck; tek satır "ok" dışında her şey ve
                      SQLiteException → StartupProblem.DATABASE_DAMAGED (satırlar hiçbir yere
                      taşınmaz; kayıtta en çok exception sınıfı); dosya yok/boş → denetim yok;
                      > 8 → önce SCHEMA_TOO_NEW; okunamaz → önce DATABASE_NOT_READABLE
kusur                 IllegalStateException, IllegalArgumentException, CancellationException,
                      Error DATABASE_DAMAGED yapılmaz, aynen yükselir; kilit yine bırakılır
dokunmayan bağlantı   ÖLÇÜLDÜ: düz salt okunur bağlantı var olan -shm'yi yeniden yazıyor ve kapalı
                      bir WAL veritabanının yanına boş -wal + -shm bırakıyordu (İş 4'ten beri sürüm
                      okumasında da). Room'dan önceki iki soru (sürüm, quick_check) artık:
                        -wal yok            → immutable=1   (log yoksa okunacak log da yok; hiçbir
                                                             dosya oluşmaz, kilit tutuluyor)
                        -wal ve -shm var    → readonly_shm=1 (log okunur, indeks yazılmaz)
                        -wal var, -shm yok  → salt okunur   (-wal yazılmaz; SQLite -shm oluşturmak zorunda)
                      URI yolunda %, ?, # kaçırılır ("veri ?#% ğüşİ" dizininde testli).
                      Klonlama (VACUUM INTO) ve klon denetimleri DEĞİŞMEDİ.
ekran                 "Veri dosyanızda bir hasar bulundu, bu yüzden PNP onu açmadı ve üzerine hiçbir
                      şey yazmadı. Yedek klasörünüzdeki yedekler de olduğu gibi duruyor. Veri
                      dosyasını ve yedekleri silmeyin; verilerinizi bir yedekten kurtarmak için
                      yardım alın." + "Verileriniz olduğu gibi duruyor…" satırı
kayıt                 startup.refused, reason=DATABASE_DAMAGED, ERROR, tam bir satır; "malformed",
                      "tasks", "quick_check", yol ve dosya adı yok
```

Testler (`DamagedDatabaseStartupTest`, `StartupRecordsTest`,
`StartupErrorScreenTest`): sağlam v8 açılır; sağlam sıcak WAL'daki commit'li satır
kaybolmaz; tablo/indeks/freelist hasarı iki denemede de `DATABASE_DAMAGED`,
Room sürücüsü hiç açılmaz, ev dizinindeki her dosya (kilit dosyası hariç) bayt
bayt aynı, yedek yok; hasarlı + sıcak WAL'da db, -wal, -shm bayt bayt aynı;
kesik dosya `DATABASE_NOT_READABLE`; hasarlı v3 için set ve migration yok, sürüm
3 kalır; ret sonrası kilit boş; kapalı DB'nin yanında dosya belirmez; tuhaf yol;
kusurlar maskelenmez. Olmayan DB'nin ilk çalıştırmada oluşması ve ikinci kopyanın
reddi `StartupGateTest`'in mevcut testleridir. Dar koşu 14 sınıf / 94.

### İş 10 / Dilim 4–8'in bilerek YAPMADIKLARI

- Okuyucu sıkılaştırılmadı; canlı DB'deki `L` çelişkileri uygulama içinden
  çözülmez (zararsız tutulur; o DB'nin yedekleri geri yüklemede reddedilir).
- Hasarlı veritabanı onarılmaz, taşınmaz, silinmez; `.recover`, `VACUUM`,
  `REINDEX`, dosya takası, uygulama içi kurtarma yok; açılışa `integrity_check`
  ve `foreign_key_check` eklenmedi.
- Room şeması, migration zinciri, instance kilidi, snapshot kapısı ve açılış hata
  ekranının yapısı değişmedi.

## Açık sınırlar ve karar bekleyenler

```text
R13 elle kurtarma belgesi         İş 14 + ayrı karar (uygulama içi kurtarma YOK)
R13 indeks-içerik uyuşmazlığı     açılışta aranmaz (bilinçli; integrity_check yalnız migration klonunda)
R13 oturum içi hasar sınıfı       ayırt edilmez (storage.*_failed)
R13 -wal var, -shm yok            salt okunur okuma için SQLite yeni bir -shm oluşturur (-wal değişmez)
R13 kesik dosya                   çoğunlukla DATABASE_NOT_READABLE olarak reddedilir (sürüm okunamaz)
R14 D6/D7, C*, RB* canlı çözüm    uygulama içinden yok; zararsız tutulur
R15 tek kopya politikası          ayrı ürün kararı; İş 10 değiştirmez
beklenmeyen hata davranışı        ölçülmedi; Dilim 2 ölçer ve korur; değiştirmek ayrı karar
Compose pencere hata handler'ı    1.11.1'de kullanılabilirliği Dilim 2'de doğrulanır
DiagnosticLogSink kapanış yarışı  KOD OKUMASI, ölçülmedi, düzeltilmedi: süre dolan close() sink'i
                                  işçinin ALTINDAN kapatırken işçi tam o anda isDisabled denetimini
                                  geçmişse bir satırı yeniden açılmış bir kanala, kilit bırakılmış
                                  olarak yazabilir (satır yine bütündür; dosya/kilit son sink.close()
                                  ile bırakılır). Pencere birkaç komut genişliğinde; ancak kapanış
                                  süresi dolduğunda açılır. Kanıtlanmış bir hata olmadığı için üretim
                                  değişmedi; ayrı karar
```

(`DiagnosticLogProcessTest` zamanlaması artık açık sınır değildir: stabilizasyon
turunda kapandı, aşağıda.)

---

# 25.5 LINUX PAKETLEME  *(Faz 3 / İş 11 ve İş 12 — TAMAMLANDI)*

## Verilmiş kararlar  *(kullanıcı adına verildi; yeniden tartışılmaz)*

```text
 1  İş 11 çıktısı: sistemde Java gerektirmeyen self-contained uygulama dizini ve onun
    belirlenimci tar.gz arşivi; JRE paketin içinde
 2  İş 12 çıktısı: pacman ile kurulabilen .pkg.tar.zst; uygulama /opt/pnp-tracker,
    başlatıcı /usr/bin/pnp-tracker, masaüstü girdisi ve ikon freedesktop konumları
 3  Sürümün tek kaynağı Gradle project.version (0.1.0 korundu, yükseltilmedi)
 4  İş 12, İş 11'in doğrulanmış arşivini paketler; ikinci derleme hattı yok
 5  Yayın, GitHub Release, AUR, imzalama, CI bu işlerin kapsamı DEĞİL
 6  Yeni üçüncü taraf bağımlılık yok; Room şeması ve migration zinciri değişmedi
```

Paket türlerinin nedeni: arşiv her dağıtımda açılıp çalışabilen, Java'sız
taşınabilir biçimdir (PLAN İş 12'nin "açıkça belgelenmiş taşınabilir paket"
seçeneği de budur); `.pkg.tar.zst` ise Garuda/Arch'ın kendi paket yöneticisiyle
kurulan, kaldırılan ve güncellenen biçimdir. İkisi aynı uygulama dizinidir.

## Sürüm — tek kaynak  *(R5 kapandı)*

`app/build.gradle.kts`: `version = "0.1.0"`. `generateApplicationVersion` görevi
bundan `dev.pnptracker.APPLICATION_VERSION` sabitini üretir (commonMain'e
kaynak olarak eklenir); `AppInfo.Current.version` onu okur — yedek belgelerine
yazılan sürüm dahil. jpackage `packageVersion`, arşiv adı, `VERSION` dosyası,
`.jpackage.xml`, başlatıcının `-Djpackage.app-version`, PKGBUILD `pkgver` ve
`.PKGINFO` hep aynı değerden türer ve iki denetim görevi her birini sınar.

## İş 11 — self-contained arşiv  *(`a942683`)*

```text
araç            Compose Multiplatform 1.11.1 nativeDistributions → createDistributable
                (jpackage uygulama imajı, Temurin 21.0.12) → stageLinuxApplication (Sync)
                → packageLinuxArchive (Gradle Tar, GZIP). Elle yazılmış runtime düzeni YOK.
çıktı           app/build/linux/dist/pnp-tracker-0.1.0-linux-x86_64.tar.gz
                94.309.676 bayt; açılmış hâli 181.014.768 bayt (runtime ≈90 MB, jar'lar ≈82 MB)
                SHA-256 a66bbb88dea52969889b6e5555e55b679827cb151fae48d0094e8c43cd958716
mimari          os.arch → uname adı (amd64 → x86_64; aarch64); başka mimaride görev durur
içerik          pnp-tracker-0.1.0/ tek üst dizin (221 girdi, 0 sembolik bağ, sahip 0/0)
                  bin/pnp-tracker            jpackage native başlatıcı (755)
                  lib/libapplauncher.so, lib/pnp-tracker.png
                  lib/app/*.jar, pnp-tracker.cfg, .jpackage.xml, libskiko-linux-x64.so
                  lib/runtime/               jlink runtime (bin/ YOK: --strip-native-commands,
                                             --strip-debug, --no-man-pages, --no-header-files,
                                             --compress); release dosyası JAVA_VERSION ve MODULES
                  README.txt                 Türkçe içerik açıklaması; LİSANS UYDURULMADI
                  VERSION                    name=pnp-tracker / version=0.1.0 / arch=x86_64
runtime modülleri (13) java.base java.datatransfer java.desktop java.instrument java.logging
                java.naming java.prefs java.security.jgss java.security.sasl java.xml
                java.xml.crypto jdk.crypto.ec jdk.unsupported
                (kök: Compose'un varsayılanı java.base/desktop/logging + jdk.crypto.ec ve
                 suggestRuntimeModules'un jdeps önerisi java.instrument, java.security.jgss,
                 java.xml.crypto, jdk.unsupported; gerisi bağımlılık kapanışı. jdk.localedata /
                 jdk.charsets gerekmedi: kod Locale.ROOT ve UTF-8 kullanıyor — tarandı)
ikon            packaging/linux/pnp-tracker.svg (elle, yazısız) + 256×256 PNG (rsvg-convert ile bir
                kez üretildi, commit'li; PNG'de metin/zaman parçası yok)
belirlenimcilik bütün AbstractArchiveTask'lar zaman damgasız ve sabit sırada. Tek değişken:
                Compose'un FileUtils.transformJar'ı skiko-awt-runtime jar'ını derleme saatiyle
                yeniden yazıyor (içinde yalnız MANIFEST), adı içerik özetini taşıdığı için .cfg de
                değişiyordu. normaliseRepackedJars hazırlık kopyasında onu aynı girdi ve baytlarla
                sabit saatte yeniden yazar, özetle adlandırır, .cfg'deki tek satırı günceller ve
                içeriğin değişmediğini denetler. Sonuç: iki temiz, cache'siz üretimde ve sonraki
                oturumlarda arşiv BAYT BAYT aynı (a66bbb88…).
sızıntı         yok: .cfg yalnız $APPDIR; depo yolu, ev yolu, kullanıcı adı, .gradle/caches,
                build/compose hiçbir baytta yok (192 dosya taranır)
```

## İş 12 — Arch paketi  *(`44b329d`)*

```text
tarif           packaging/arch/PKGBUILD (şablon: @PKGVER@ @ARCH@ @ARCHIVE@ @…_SHA256@) +
                packaging/arch/pnp-tracker.desktop
görev           :app:packageArch — şablonu doldurur, arşivi ve masaüstü girdisini
                $TMPDIR/pnp-tracker-makepkg/ altına koyar (yalnız kendi işaret dosyasını taşıyan
                dizini siler), makepkg --nodeps --noconfirm --nosign --force --clean'i kendi
                HOME'u, PATH=/usr/bin:/bin, SOURCE_DATE_EPOCH=0, PACKAGER="pnp-tracker local build
                <build@pnp-tracker.invalid>" ile çalıştırır; paketi app/build/arch/dist/'e alır.
                pacman'e hiç dokunmaz; uygulama yeniden derlenmez (kaynak = İş 11 arşivi)
çıktı           pnp-tracker-0.1.0-1-x86_64.pkg.tar.zst  92.537.371 bayt, kurulu 181.025.904
                SHA-256 eb296a35f43f352ba1da02ff25abfd5985f56c84afd1af14e3c805f0abe870ea
                (iki ardışık üretimde aynı)
yerleşim        /opt/pnp-tracker/                                   uygulama dizini (root, 755/644)
                /usr/bin/pnp-tracker -> ../../opt/pnp-tracker/bin/pnp-tracker   (göreli bağ)
                /usr/share/applications/pnp-tracker.desktop
                /usr/share/icons/hicolor/256x256/apps/pnp-tracker.png
depends         glibc libstdc++ libglvnd libx11 libxext libxi libxrender libxtst fontconfig
                — her açılışta yüklenen libskiko ve libawt_xawt'nin NEEDED listesi (objdump +
                pacman -Qo). alsa-lib (yalnız libjsound) ve libsplashscreen'in kütüphaneleri
                uygulama tarafından hiç yüklenmez → eklenmedi. GTK3 yığını AWT tarafından
                varsa dlopen ile yüklenir, yoksa geri çekilir → eklenmedi. Sistem Java'sı YOK.
options         !strip !debug (sistem makepkg.conf strip+debug açık; gömülü JDK ve Skia
                soyulmamalı/bölünmemeli)
license         LicenseRef-unknown — lisans henüz seçilmedi (İş 15); uydurulmadı
masaüstü        Name=PnP Üretim Takipçisi, Exec=pnp-tracker, Icon=pnp-tracker, Terminal=false,
                Categories=Utility;, StartupWMClass=dev-pnptracker-MainKt (pencerede ölçüldü);
                desktop-file-validate temiz
kurulum betiği  YOK (.INSTALL yok): HOME'a yazmaz, uygulamayı başlatmaz, kullanıcı verisine
                dokunmaz
```

## Doğrulama  *(her ikisi de ekran ister; `check`'e bağlı değil)*

`:app:verifyLinuxPackage` ve `:app:verifyArchPackage` aynı programı çalıştırır
(`desktopTest/.../packaging/LinuxPackageCheck.kt`); her şey depo dışında, bu
koşunun yarattığı ve sonunda sildiği tek bir `/tmp/pnp-package-check-*`
dizininde olur.

```text
statik          dosya adı = sürüm + mimari; girdiler tek üst dizinde (arşiv) / yalnız opt ve usr
                altında (paket), .. ve mutlak yol yok, sembolik bağ hedefi içeride, sahip 0/0,
                grup/diğerleri yazamaz; beklenen dosyalar; başlatıcı herkes için çalıştırılabilir;
                .cfg sınıf yolu yalnız $APPDIR, app.runtime yok, mutlak yol yok; sürüm .cfg,
                .jpackage.xml, uygulama jar'ı, VERSION, .PKGINFO'da; runtime kök modülleri var,
                araç modülü (jdk.compiler, jdk.jlink, …) ve runtime/bin yok; /usr/lib/jvm,
                JAVA_HOME, /usr/bin/java referansı yok; her baytta depo/ev yolu, kullanıcı adı
                (metinde), .gradle/caches yok; .kt/.class yok; .PKGINFO/.BUILDINFO sızıntısız;
                paket için desktop-file-validate ve ikonun uygulama ikonuyla aynı olması
sistem Java'sı  runtime'ı çıkarılmış bir kopya, JAVA_HOME ve PATH'te gerçek bir JDK varken
                "Failed to find JVM in …/lib/runtime" ile 1 döner, hiçbir veri yazmaz
yoklama         gerçek başlatıcı, JAVA_TOOL_OPTIONS=-javaagent ile PackageRuntimeProbe: java.home
                ve /proc/self/maps'teki libjvm.so paketin içinde; XLSX (sample-import.xlsx:
                2 sayfa, 23 hücre), CSV yaz/oku (UTF-8, BOM, Türkçe), Room + gömülü SQLite +
                JSON yedek yaz/oku/geçici DB'ye yükle, tanılama satırı, AWT fontları (582 aile,
                Türkçe çizim), Skia native + FontMgr (595 aile, Türkçe çizim); sonra süreç biter
pencere          gerçek başlatıcı (paket için /usr/bin/pnp-tracker, geçici kökte /opt'a çözülür);
                ortam temizlenir: PATH boş bir dizin, JAVA_HOME yok, HOME/cwd/XDG_DATA/CONFIG/STATE
                ve java.io.tmpdir geçici; pencere tam başlık + süreç ağacı ile SafeWindowCloser'la
                kapanır; /proc/<pid>/exe = paketin başlatıcısı, libjvm.so ve bütün Java
                kütüphaneleri paketten; çıkış 0; pnp.db var, -wal/-shm yok; state boş; cwd, HOME
                ve tmp boş; çıktıda exception yok; süreç kalmaz
paket ek        çalıştırmadan sonra /opt/pnp-tracker'ın her dosyası bayt bayt aynı (geçici kökte
                kullanıcı yazabilirken bile yazılmadı); paket dosyaları pacman gibi silinince
                kök temizlenir, geçici data/config/state bayt bayt aynı kalır
```

Son koşularda gözlenen (tam koşudan sonra, `a0d1aa6`): iki görev de
`PACKAGE: PASSED`; pencere `WM_CLASS` = `dev-pnptracker-MainKt`; çalışan uygulama
89 sistem kütüphanesi eşledi (GTK3, pango, cairo, nvidia-utils GL sürücüsü dahil —
isteğe bağlı yüklemeler).

## Bilinen taşınabilirlik sınırları

```text
glibc/x86_64    jpackage başlatıcısı ve runtime bu makinenin glibc'siyle bağlı; musl ve başka
                mimari desteklenmez; arşiv üretildiği mimaride çalışır
X11             AWT X11 araç takımı; Wayland oturumunda XWayland gerekir
depends         libGL (libglvnd + bir GL sürücüsü) ve fontconfig zorunlu; GTK3 yoksa AWT
                masaüstü temasını okuyamaz ama çalışır (ölçülmedi: GTK'siz sistem yok)
.BUILDINFO      makepkg her pakete derleme makinesinin KURULU PAKET LİSTESİNİ (1365 satır) ve
                buildenv/options'ı yazar — Arch'ın tekrarlanabilir derleme standardı, kapatılamaz;
                kişisel yol/ad içermez (builddir/startdir = /tmp/pnp-tracker-makepkg/…)
belirlenimcilik aynı makine + aynı araçlar içinde kanıtlandı; farklı JDK/zstd/makepkg sürümü farklı
                bayt üretebilir
log4j           POI'nin log4j-api'si ilk XLSX okumasında stderr'e "Log4j API could not find a
                logging provider" yazar (geliştirme sürümünde de aynı; exception değil)
namcap          bu makinede YOK; talimat gereği kurulmadı → yerine yapısal denetim (yukarıda)
temiz ortam     gerçek pacman -U kurulumu, güncelleme ve kaldırma İş 13'tür (R6)
```

---

# 26. DB / ŞEMA KORUMA

```text
Room şema sürümü = 8
12 seed renk
```

İlgili dilim **açıkça istemedikçe** dokunulmaz:

- `1.json` … `8.json`
- Room entity'leri, DAO'lar, migration'lar
- `PLAN.md`
- POI sürümü, Gradle wrapper, version catalog

Ek kurallar:

- `fallbackToDestructiveMigration` **asla** eklenmez.
- Şema değişikliği migration ve test olmadan commit edilmez (PLAN `19.` senaryo notları).
- Migration beklenmeyen satırlarla karşılaştığında sessizce veri silmez; ya kaydı
  korumalı biçimde dönüştürür ya da anlaşılır bir hatayla durur — hangisinin
  seçildiği migration'ın kendi belgesinde yazılıdır (PLAN `18.` gerçek veri notları).
- Entity/migration/schema JSON değişikliği gerektiğini düşünürsen **uygulamadan önce
  dur ve somut kanıtlarla bildir.**

Onaylanmış tek istisna **kullanılmıştır ve kapanmıştır:** Faz 3 / İş 2'nin
birinci dilimi Room v8'e geçti ve `import_batch_cells` tablosunu ekledi. Mevcut
hiçbir tablo, sütun veya index değişmedi; `1.json` … `7.json` bayt bayt aynı
kaldı, `8.json` eklendi. `Migration7To8` backfill yapmaz — yalnız tabloyu ve
`index_import_batch_cells_cell_id` indeksini oluşturur.

Bundan sonrası için kural yeniden yürürlüktedir: **yeni bir şema değişikliği
gerektiğini düşünürsen uygulamadan önce dur ve kanıtlarıyla bildir.**

**Faz 3 / İş 3 şema değişikliği gerektirmedi** ve dört diliminin hiçbirinde
gerektirmedi: yedekleme yalnız okur, geri yükleme var olan tablolara satır
yazar. Bu, restore'un geçmişe olay yazmamasının da sebeplerinden biridir —
`history_events.game_id` NOT NULL ve `games`'e FK'lidir, yani oyunu olmayan bir
"geri yükleme yapıldı" satırı ancak Room v9 ile yazılabilirdi (§25.1).

**Faz 3 / İş 4 de şema değişikliği gerektirmez** ve dört diliminin hiçbirinde
gerektirmeyecektir:

- Otomatik snapshot, manuel yedekle **aynı** okuma yolunu ve **aynı** kanonik
  biçimi kullanır; yeni tablo, sütun veya index yoktur.
- Saklama ayarı bilinçli olarak veritabanının **dışındadır** (`settings.json`),
  dolayısıyla 15 tablolu yedek kapsamı da değişmez (§25.2).
- Otomatik snapshot geçmişe olay yazmaz; aynı `history_events.game_id`
  gerekçesiyle yazamaz da.
- Migration snapshot'ı **var olan** migration zincirini çalıştırır; yeni bir
  migration yazmaz. Zincir `Migration1To2` … `Migration7To8` olarak kalır.

**Ve gerektirmedi:** İş 4 dört diliminin hiçbirinde şema değişmedi. `1.json` …
`8.json` bayt bayt aynı kaldı, Room sürümü 8 kaldı, zincir aynı kaldı. Dilim 4
migration yazmadı; **var olan zinciri iki kez çalıştırdı** — bir kez geçici bir
çalışma kopyası üzerinde, bir kez gerçek veritabanı üzerinde.

Bundan sonrası için kural yeniden yürürlüktedir: **yeni bir şema değişikliği
gerektiğini düşünürsen uygulamadan önce dur ve kanıtlarıyla bildir.**

## Migration testi kalıbı

Her migration için: dolu fixture yürüyüşü + boş fixture yürüyüşü + v1'den güncel
sürüme zincirleme yürüyüş + `PRAGMA user_version` + `foreign_key_check` +
`integrity_check` + eski `.json` dosyalarının değişmediği + yeni `.json`'ın gerçek
şemayla eşleştiği. `CommittedSchema` eski sürümleri **commit edilmiş** JSON'lardan
kurar, elle yazılmış kopyalardan değil.

---

# 27. GERÇEK XDG DB REGRESYON KONTROLÜ

```text
dilim başında : SHA-256 + boyut + mtime
dilim sonunda : SHA-256 + boyut + mtime
beklenti      : üçü de aynı
```

- Gerçek `pnp.db` açılmaz, kopyalanmaz, migrate edilmez, silinmez, sıfırlanmaz.
- Testler ve manuel turlar yalnız geçici Room veritabanları ve geçici XDG dizinleri
  kullanır.
- Temizlikte silinecek geçici dizinin kapsamı tam olarak doğrulanır; geniş veya
  belirsiz silme yapılmaz.

Repoya **girmemesi gerekenler**: gerçek kullanıcı Excel dosyası, gerçek üretim verisi,
gerçek veritabanı, kişisel mutlak dosya yolu, e-posta, secret/API key, build çıktısı,
ekran görüntüleri.

---

# 28. FIXTURE

```text
app/src/desktopTest/resources/sample-import.xlsx
SHA-256 314780a48e5002b2ffaef6856c63d2e42a55759d39ac485b8f00753f551d1833
```

Fixture anonimleştirilmiş ve küçültülmüştür; gerçek kullanıcı dosyasının yerine
geçmez. Hash'i dilim başında/sonunda kontrol edilir ve kapsam dışında değiştirilmez.

---

# 29. TEST VE ÖLÇÜM ALTYAPISI

```text
TemporaryDatabaseDirectory              geçici Room DB + gerçek DB koruma iddiası
assertRealApplicationDatabaseUntouched  113 test sınıfında kullanılıyor
CommittedSchema                         eski sürümleri commit'li JSON'dan kurar
LegacyRowFixtures                       v1…v6 satır yazıcıları
                                        (v6 raw block = v7 raw block; şema aynı)
CountingSqliteDriver                    gerçek sürücü seviyesinde ifade sayımı
FailingSqliteDriver                     enjekte edilen depolama hataları
PausingSqliteDriver                     transaction ortasında kontrollü araya girme
BackupFixture                           15 tablonun hepsini dolduran, istenirse tablo
                                        içinde TERS sırayla yazan yedek fixture'ı
BackupManifest (desktopTest)            yedek biçiminin elle beyan edilmiş sözleşmesi;
                                        8.json ve serializer descriptor'larıyla
                                        karşılaştırılır
BackupDocuments (commonTest)            her tabloda satırı olan geçerli bir yedek +
                                        boyutu/akışı yalan söyleyebilen FakeBackupInput
                                        + sorulup sorulmadığını sayan CountingProbe
RestoreDoubles (commonTest)             restore akışının dışındaki her şeyi durduran
                                        çiftler: kapıyı tutabilen kaynak gateway'i,
                                        yazımı sayan güvenlik yedeği, transaction'ı
                                        sayan restorer, ve GERÇEK okuyucudan geçerek
                                        ValidatedBackup üreten aValidatedBackup
FakeBackupDirectory (commonTest)        diski olmayan bir yedek klasörü; belirli
                                        dosyaların silinmesini REDDEDEBİLİR, böylece
                                        fail-open ve crash/retry davranışı ölçülebilir
FakeSettingsStore (commonTest)          diski olmayan ayar deposu; yazmayı bir kapıda
                                        bekletebilir ve tipli hatayla reddedebilir
SceneSettings (desktopTest)             Compose sahnesi için duran ayar
NeverDeletes (desktopTest)              her silmeyi reddeden gerçek klasör sargısı
SnapshotDoubles (commonTest)            diski olmayan snapshot yazıcıları: yazmayı
                                        reddeden, yazdığını BOZAN (geri okunanı
                                        değiştiren) ve gerçek adı üreten; ayrıca
                                        sayan bir AutomaticSnapshotTaker
ConfirmationSnapshots (desktopTest)     gerçek DB'yi okuyup dosya yazmayan
                                        LiveSnapshotTaker — `stale = true` ile
                                        yarış penceresini AÇIK tutar — ve her
                                        testin kullandığı confirmationStore(...)
RefusingWriter / RuiningWriter /        gerçek diskte: yazmayan, yazdıktan sonra
GatedWriter (desktopTest)               dosyayı bozan, ve ikinci basış gelene
                                        kadar yazımı bekleten yazıcılar
StartupTestSupport (desktopTest)        openWithHotWal — CHECKPOINT EDİLMEMİŞ bir
                                        WAL bırakan eski veritabanı kurucusu;
                                        crashedCopyOf — üçlüyü kaza fixture'ı
                                        olarak kopyalar (mekanizma DEĞİL);
                                        Room'suz rowCountsOf / schemaVersionOf
LockHolder (desktopTest)                GERÇEK ikinci process: kilidi alır, bekler,
                                        öldürülür — OS'in kilidi bıraktığını
                                        kanıtlamanın tek dürüst yolu
StoppingSqliteDriver (desktopTest)      seçilen ifadenin N'inci çalışmasında
                                        transaction bağlantısını teslim eder ve
                                        ASLA devam etmez (öldürülecek süreç için)
InterruptedWriter (desktopTest)         GERÇEK ikinci process: Main gibi kurulur,
                                        tek yazma yapar, protokol satırı söyler,
                                        latch'te bekler ya da normal kapanır
RecoveryHome / WriterProcess            test başına geçici XDG evi; child
(desktopTest)                           başlatma, olay tabanlı satır bekleme,
                                        SIGKILL + waitFor, kapıdan yeniden açılış,
                                        gerçek uygulama dosyalarının açılmadan
                                        parmak izi
DraftRemovalFixtures (desktopTest)      her boyutta gerçek inceleme yollarıyla kurulmuş
                                        taslak; bütün DB'nin kanonik okuması ve bir
                                        taslağın kendi satırları çıkarılmış hâli
                                        (withoutDraft); foreign_key_check +
                                        integrity_check; tuzak kurmak için ham SQL
ComposeSceneHarness                     gerçek Compose sahnesi (desktopTest)
LogHome (desktopTest)                   geçici state evi + gerçek XDG konumlarının
                                        açılmadan öncesi/sonrası karşılaştırması
FaultyLogFileSystem (desktopTest)       log dosya sistemi arayüzünün önüne konan,
                                        seçilen işlemi IOException / bozuk kod ile
                                        düşüren veya eklemeyi bekleten çift; ayrıca
                                        diske inen satırları bildirir (awaitLines,
                                        awaitLineWith), ilk k yazmadan sonrasını
                                        kapıda tutar (appendsBeforeGate,
                                        awaitHeldAtGate) ve ilk satırı / kilit reddini
                                        haber verir — işçiyle HIZ DEĞİL OLAY üzerinden
                                        konuşmanın tek yolu
DiagnosticLogWriterProcess (desktopTest) GERÇEK ikinci süreç: log kilidini tutar,
                                        bekler, öldürülür ya da kayıt patlatır; her
                                        adımı olduktan sonra söyler (HELD/REFUSED,
                                        WROTE/REFUSED, "go" bekler, DONE <n>)
SafeWindowCloser (desktopTest)          masaüstünde yalnız bu çalıştırmanın penceresini
                                        kapatan tek yol: tam başlık + süreç ağacı +
                                        hemen önce yeniden doğrulama; kabuk yok (§25.4)
DesktopWindowSmoke (desktopTest)        ./gradlew desktopWindowSmoke — geçici XDG ile
                                        gerçek pencere aç/kapat; check'e bağlı değil
```

Beş smoke turu (`BackupSmokeTest`, `BackupRestoreSmokeTest`, `RestoreSmokeTest`,
`RetentionSmokeTest` ve Dilim 3'ün `ImportSnapshotSmokeTest`'i),
`TemporaryDatabaseDirectory` örneği tutmadıkları için aynı iddiayı **satır
içinde** kurar: gerçek veritabanının var olup olmadığı turdan önce ölçülür ve
sonra karşılaştırılır. Bu yüzden yukarıdaki
94 sayısı yardımcı fonksiyonun kendi sayısıdır, korumanın değil.

**Sorgu sayımı her zaman frekans haritasıyla yapılır**, `Set` ile değil: `Set` bir
ifadenin 400 koşusunu bire indirir ve kimsenin ödemediği bir maliyeti raporlar.
Sayımdan önce bağlantı ısıtılır, aksi hâlde şema okumaları sayımı kirletir.
`room_table_modification_log`, transaction sözcükleri ve `PRAGMA` filtrelenir.

## Korunması gereken ölçülmüş invariant'lar

```text
Oyun tablosu             4 sabit ifade
Havuzlar                 3 / 3 / 3 / 2 sabit ifade  (kenar çubuğu özeti 1)
Arama ve filtre değişimi 0 ek ifade
CSV export               {SELECT tasks=1, SELECT task_colors=1}, N'den bağımsız
                         export sırasında 0 INSERT/UPDATE/DELETE
Geçmiş yazımı            görev başına yeni SELECT yok; toplu işlemde karar sorguları
                         N ile büyümez; geçmişe yapılan tek şey INSERT'tür
Geçmiş ekranı            2 SELECT (history_events + progress_events), satır,
                         görev ve oyun sayısından bağımsız; süzgeç değişimi 0 ek
                         ifade
İçe aktarma onayı        karar sorguları taslak sayısıyla büyümez; hücre anlık
                         görüntüsü 1 INSERT/hücre ve toplam 1 SELECT — bir
                         hücreye 42 taslak yine tek satır yazar
Otomatik import snapshot İKİ tam okuma (yedek + transaction içi kapı), taslak
                         sayısından BAĞIMSIZ: 1 ve 42 taslak aynı ifadeleri
                         çalıştırır; kapı yalnız okur, yazımı değiştirmez
Kesintiye dayanıklılık   dört yazma yolu (taslak kaydı, düzenleme, onay, geri
                         alma) transaction İÇİNDE SIGKILL → 15 tablonun parmak
                         izi işlem öncesiyle AYNI; commit SONRASI SIGKILL →
                         commit edilenle AYNI; her iki durumda integrity_check
                         ok, foreign_key_check boş, kapıdan açılış, set yok
Kalıcılık ayarı          writer ve reader: journal_mode = wal, synchronous = 1;
                         başlık baytları 18/19 = 2/2; yeniden açılışta aynı
Taslağı kaldırma         0, 1 ve 42 hücreli (×2 taslak ×3 renk) taslak AYNI ifade
                         haritasını çalıştırır: SELECT batch 1, SELECT facts 2,
                         SELECT 15 tablo sayımı 2, DELETE 1 (+ SAVEPOINT çifti ve
                         changes()); satır başına okuma veya silme YOK; ret yolu
                         yalnız 1 SELECT, 0 yazma; transaction BEGIN IMMEDIATE
                         (sıra ile ölçüldü). Kaldırma sonrası DB = önce − o batch'in
                         kendi satırları, DEĞER düzeyinde; history 0 satır,
                         backups/ 0 dosya
Taslak sınıflandırma     draftHealthOf 1 blok ve 42 blok × 2 taslak için (sağlıklı
                         da, 84 seçimin hepsi metin dışında da) AYNI üç okuma:
                         batch 1, altı sayım 1, aday seçimler 1; NotFound /
                         NotADraft yalnız batch okuması; BEGIN IMMEDIATE (sıra ile
                         ölçüldü); 0 yazma. Onay sınıflandırıcıyı TAM 2 kez çağırır
                         (snapshot'tan önce + transaction içinde); bozuk taslakta
                         snapshot 0, rotation 0, DB aynı. D1–D9'un dokuzu da gerçek
                         restore hattından canlı DB'ye ULAŞIR (ölçüldü)
Devam eden içe aktarmalar healthOfDraftBatches 1 ve 42 taslak için AYNI üç okuma
                         (drafts 1, counts 1, selections 1), boş listede 1; tek
                         transaction; 0 yazma; tekil draftHealthOf ile satır satır
                         eşdeğer. Devam et her basışta draftHealthOf'u YENİDEN
                         okur; kaldırma motoru dışında yazan yol yok, snapshot 0,
                         history 0
Tanılama sınırları       her tipli hata sınırı TAM BİR kayıt üretir ve controller'ı ikinci
                         kez yazmaz (matris testleri); başarılı uçtan uca tur 0 satır ve
                         state dizini bile YOK; kayıt transaction bittikten SONRA verilir
                         (kaydederken yeni bir yazma transaction'ı hemen açılabiliyor);
                         8 eşzamanlı aynı hata 8 tam satır; 0 ek SQL ifadesi
Gözlenen okuma reddi     oyun tablosu, renk kataloğu ve havuz akışlarında YALNIZ
                         SQLiteException tipli "okunamadı"ya çevrilir ve TEK satır yazar;
                         IllegalState/IllegalArgument/NullPointer/Error ve
                         CancellationException AYNEN yükselir ve kaydedilmez; bir ret akışı
                         BİTİRİR, tek satır yazar ve yalnız `readAttempt` yeni bir toplama
                         başlatır; gelen ilk başarılı okuma hata durumunu temizler; reddedilen
                         okuma ASLA boş liste/boş tablo olarak çizilmez (görünüm ve süzgeç
                         değişimi dâhil). Üç controller'da `.catch {` ve geniş
                         `catch (Throwable|Exception)` YOK (yapısal test).
                         IllegalArgument ve Error için ayrı testler (stabilizasyon turu):
                         aynı nesne yükselir, ekran Loading kalır, seam cevap vermez
Tanılama kapanışı        diske inen her satır bütün; sürecin satırları seq 1…n boşluksuz
                         önek (kayıp yalnız SONDA); dosyadaki satırlar = yazanın kendi
                         disk çağrılarıyla yazdıkları; kilidi alamayan 0 satır; close()
                         ≤ 1.500 ms, işçi biter, kilit/dosya bırakılır; süre dolan kayıt
                         sayılmaz ve rapor satırı yazılmaz. Testler makinenin 500 ms'de
                         kaç satır yazdığını ÖLÇMEZ (olay tabanlı; %25 CPU kotasında geçer)
Masaüstü pencere         smoke yalnız SafeWindowCloser ile kapatır: kısmi başlık, başka
                         süreç, sıfır/çok aday, güvenilmez liste → HİÇBİR istek; repoda
                         başka pencere aracı kullanımı YOK (kaynak taraması)
Açılış dizinleri         data/backups/config oluşturulamazsa StartupRefused
                         (FOLDERS_NOT_CREATED): DB ve StartupGate HİÇ açılmaz, kullanıcı
                         alanında dosya oluşmaz, uygulamanın yazdığı hiçbir cümlede MUTLAK
                         YOL yoktur, tek `startup.refused` satırı yalnız sınıf adları taşır;
                         state alanı da yazılamazsa ikinci bir hata OLUŞMAZ
Tanılama kaydı           kayıt verilmeyen yazıcı diskte hiçbir şey oluşturmaz; record()
                         disk I/O yapmaz; 16.000 eşzamanlı kayıt seq sırasıyla, satırlar
                         karışmadan; dosya ≤ 1 MiB (tam 1 MiB'a izin), ≤ 5 dosya; yabancı
                         nesne silinmez; hiçbir hata çağırana ulaşmaz; SQL ifadesi 0
Kapanış izleri           normal kapanış ile SIGKILL sonrası ilk açılış AYNI dosya
                         listesini bırakır; işaret/kurtarma dosyası yok
Açılış kapısı            v8 bir veritabanında EK MALİYET YOK: yalnız kilit +
                         Room'suz tek PRAGMA okuması; klon, çalışma kopyası ve
                         belge SADECE sürüm 1..7 ise üretilir.
                         İş 10 / Dilim 8'den beri sürüm 1..8'de her açılışta
                         tek salt okunur PRAGMA quick_check (dosya boyutuyla
                         doğrusal; ölçülen: 1.203 görev ≈ 1,5 ms, 12.030 görev
                         ≈ 12 ms — kayıt, eşik değil; §25.4)
Geri alma                önizleme 1 ve 42 görev için AYNI ifadeleri çalıştırır;
                         geri alma sorguları görev/hücre sayısıyla büyümez ve
                         geçmiş uzadıkça artmaz. Yazımlar büyür: görev başına
                         tombstone, görev+oyun başına geçmiş satırı, sonek
                         başına DELETE
Onaylanmış batch listesi {SELECT import_batches = 1}, başka tablo yok. 1 ve 42
                         batch aynı ifadeleri çalıştırır; 42 görev üreten batch
                         listede tek satırdır (JOIN yok, çoğaltma yok)
Yedek anlık görüntüsü    15 SELECT — tablo başına bir okuma, satır başına hiçbiri.
                         3 görevli ve 1.203 görevli veritabanı AYNI ifadeleri
                         çalıştırır. Yedek sırasında 0 INSERT / UPDATE / DELETE.
                         Ölçüm: 1.203 görev → 1.289.227 karakter, 122 ms,
                         ~23 MiB (bu makinede; eşik değil kayıttır)
Yedek dosyaya yazma      hedef seçilmeden 0 SELECT; onay reddedilirse 0 SELECT ve
                         0 bayt; yazma sırasında DB'de 0 değişiklik. Yazıcının üç
                         dikişinin her birinde mevcut hedef bayt bayt korunur ve
                         geçici dosya kalmaz
Yedek okuma — sınırlar   tam 64 MiB KABUL, +1 bayt RET; bildirilen boyut sınırın
                         üstündeyse dosya AÇILMAZ; boyut, UTF-8, JSON, zarf,
                         checksum, değer ve graf hatalarının HİÇBİRİNDE geçici
                         veritabanı oluşturulmaz (probe 0 kez sorulur); stream
                         başarı, ret ve yarı yolda kesilme yollarının hepsinde
                         kapatılır
Yedek okuma — geçici DB  yükleme tablo başına 1 prepared statement, satır başına
                         0 SELECT; geri okuma 15 SELECT (tablo başına bir).
                         3 görevli ve 1.003 görevli yedek AYNI ifade kümesini
                         çalıştırır — yalnız INSERT sayısı büyür.
                         Ölçüm (bu makinede; eşik değil kayıttır):
                         1.003 görevli yedek → dosya 458.657 bayt (~448 KiB),
                         64 MiB sınırının ~150'de biri. Uçtan uca
                         yazma+okuma+geçici DB testi 0,46 s; küçük ve büyük
                         yedeğin geçici DB denemesi birlikte 0,22 s
Yedek okuma — temizlik   db, -wal, -shm ve geçici dizin başarı, insert hatası ve
                         geri okuma hatası yollarının hepsinde silinir
Canlı restore            15 DELETE + tablo başına 1 prepared INSERT + 45 SELECT
                         (15 tablo × 3: güvenlik karşılaştırması, transaction içi
                         postcondition, commit sonrası doğrulama). 3 görevli ve
                         1.003 görevli yedek AYNI ifade kümesini çalıştırır; yalnız
                         INSERT sayısı satırla büyür. Satır başına SELECT YOK.
                         Room'un kendi bakım sorguları (`room_…`, `sqlite_master`)
                         sayımdan çıkarılır: onları Room kendi zamanlamasıyla
                         çalıştırır ve saymak makineyi ölçmek olurdu
Restore — atomiklik      DELETE, INSERT ve geri okuma noktalarının her birine
                         enjekte edilen SQLite hatası bütün 15 tabloyu ÖNCEKİ
                         hâlinde bırakır (kanonik okuma ile karşılaştırılır)
Restore — yarış          güvenlik anlık görüntüsünden sonra değişmiş bir DB
                         hiçbir şey yazmadan reddedilir; transaction sürerken
                         başlatılan ikinci yazma araya giremez, restore bittikten
                         SONRA çalışır
Restore — invalidation   restore öncesinde açılmış Oyun tablosu, Renkler, Geçmiş,
                         onaylanmış içe aktarmalar ve dört havuz + kenar çubuğu
                         akışlarının hepsi restore edilmiş veriyi YENİDEN yayar
                         (10 s zaman aşımıyla beklenir, örneklenmez)
Restore — ölçüm          (bu makinede; eşik değil kayıttır)
                         3 ve 1.003 görevli yedeğin ifade-şekli karşılaştırması
                         birlikte 0,228 s; canlı restore + doğrulama testlerinin
                         tamamı 0,483 s; A→B→A→B uçtan uca yaşam döngüsü 0,181 s;
                         geçici XDG smoke turu 0,079 s
Güvenlik yedeği          onaydan önce 0 dosya; vazgeçildiğinde 0; doğrulama
                         reddinde 0; onaydan sonra canlı yazımdan ÖNCE 1.
                         Aynı saniyedeki ikinci yedek üzerine YAZMAZ, -2 alır.
                         Yazma/taşıma hatasında ne .json ne .part kalır
```

## Faz 3 / İş 9 — büyük veri performansı  *(TAMAMLANDI — makineden bağımsız kabul ölçütleriyle)*

PLAN `18.` Faz 3 testi "1.000+ görevle açılış, arama ve havuz filtreleme
performansı" **eşik vermez**. Bu yüzden milisaniye veya bellek sınırı
uydurulmadı; süre ve bellek yalnız **kayıttır**. Testin geçme koşulu makineden
bağımsız olanlardır. Test: `desktopTest/…/performance/LargeLibraryPerformanceTest` (4 test).

Veri kümesi kodla ve uygulamanın **kendi yazma yollarıyla** kurulur
(`addTaskToCell`, `addColorToTask`, `reportFailure`, `setStageQuantity`,
`convertTaskToText`, `GameDao.softDelete`) ve DB gerçek `StartupGate` ile geçici
XDG evinde açılır:

```text
görev          1.203 yazıldı → 1.146 yaşayan (47 metne dönüştürülmüş + silinmiş
               oyunun 10 görevi hariç); 121 oyun (1 silinmiş), oyun başına 5 hücre
havuz          4 havuza eşit dağılım (287 / 287 / 287 / 285)
durum          tamamlanmış (her 3.), bilgi eksik + adetsiz (her 13.), açık
bayrak         MISSING (i%10=1), BORROWED (i%10=2)
renk (3D)      0 / 1 / 2 / 3 renk; 4 tohum + 1 özel ("Açık Şeftali")
ilerleme       3D'de FAILURE_REPORTED, kart/mukavvada PRINT aşaması; geçmiş 179 satır
metin          Türkçe büyük/küçük (Işık/IŞIK/ışıltı, İstanbul/istanbul/Istanbul),
               birleşik aksan (Cafe + U+0301) ve hazır "Café", emoji + ZWJ, virgül,
               tırnak, çok satırlı not
```

Geçme koşulları (hepsi geçti):

```text
açılış          42 ve 1.203 görevde AYNI normalize SQL frekans haritası:
                kapı 3 ifade (room_master_table ×2 + tek COUNT(*) FROM games)
                kenar özeti 1 · oyun tablosu 4 · renk kataloğu 1 · 3D/kart/mukavva
                havuzu 3'er · özel havuz 2 · geçmiş 2 · devam eden içe aktarmalar 1 ·
                onaylanmış içe aktarmalar 1  → toplam 21; hiçbir ifade bir okumada
                iki kez çalışmaz (satır/oyun/renk başına okuma YOK)
sonuç doğruluğu her havuzun görev kimlikleri = kurulumdan hesaplanan küme; kenar
                özeti task/active sayıları; tablo = silinmemiş 120 oyun ve 1.146
                görev parçası
tekrar          aynı okuma 1. ve 42. kez: aynı ifadeler, eşit sonuç (10 okuma × 42)
arama           7 sorgu × 4 havuz + tablo: sonuç kümesi kurulumdan hesaplanan
                beklentiye eşit (ışık = IŞIK, istanbul = İSTANBUL ≠ Istanbul,
                café = cafe+U+0301, oyun adı eşleşmesi, boş sonuç); 0 ifade;
                controller'a harf harf yazma 0 ifade
havuz süzgeci   24 süzgeç (durum × 4 havuz, 5 renk, renk seçilecek, renk + renk
                seçilecek, MISSING, MISSING|BORROWED + tamamlanmış, PRINT, LAMINATE,
                arama + renk + durum): beklenen küme; 0 ifade; ekrandaki değişim 0 ifade
N+1             bulunmadı; üretim kodu DEĞİŞMEDİ
```

Ölçüm kaydı — **eşik değildir**, makineye bağlıdır (Intel i5-10300H, 8 çekirdek,
7,6 GiB RAM, Linux 7.2.4-zen, JDK 21.0.12, test JVM maxHeap 512 MiB; Gradle test
görevi içinde, ısınmamış JVM):

```text
kapıdan yeniden açılış          42 → 2,3 ms     1.203 → 2,1 ms
kenar özeti (ilk)               42 → 1,4 ms     1.203 → 4,5 ms
oyun tablosu (ilk, 120 satır)   42 → 7,3 ms     1.203 → 16,5 ms
havuzlar (ilk)                  1.203 → 3D 5,4 · kart 4,1 · mukavva 3,6 · özel 3,0 ms
geçmiş (ilk, 179 satır)         42 → 1,0 ms     1.203 → 2,8 ms
tekrar okuma medyanı (×41)      tablo 15,9 · 3D 5,1 · kart 5,2 · geçmiş 3,0 ms
okumaların tuttuğu heap          ≈ 1,1 MiB (System.gc sonrası fark; kaba)
arama 7 sorgu × 4 havuz + tablo  ≈ 110 ms toplam (beklenti hesabı dâhil)
havuz süzgeci 24 süzgeç          ≈ 22 ms toplam
11 harf yazma (havuz + tablo)    ≈ 14 ms
```

**Karar (İş 10 belge turu, PLAN `18.` Faz 3 / İş 9):** duvar saati ve bellek
miktarı CI geçme eşiği **değildir**; donanım, JVM ısınması ve sistem yükü bu
değerleri güvenilir bir test kapısı olmaktan çıkarır. Bağlayıcı kabul ölçütleri
ve `14490a8`'deki kanıtı:

```text
ölçüt                                          kanıt (LargeLibraryPerformanceTest)
≥1.000 görevli gerçekçi kümede doğru sonuç     1.203 görev, kendi yazma yolları; havuz
                                               kümeleri, kenar özeti ve tablo kurulumdan
                                               hesaplanan beklentiye eşit
42 ve 1.000+ görevde aynı SQL ifade yapısı     kapı 3 + 10 ekran okuması = 21 ifade, iki
                                               ölçekte aynı normalize frekans haritası
açılış/ana ekranlarda N+1 yok                  hiçbir ifade bir okumada iki kez çalışmaz
arama ve havuz süzgeci ek SQL üretmez          7 arama × 4 havuz + tablo, 24 süzgeç,
                                               controller'a harf harf yazma: 0 ifade
tekrarlı okuma aynı sonucu verir               10 okuma × 42 tekrar: aynı ifadeler, eşit sonuç
süre/bellek yalnız ortamla birlikte kayıt      PERF satırları ortam satırıyla basılır;
                                               yukarıdaki tablo işlemci, bellek, çekirdek
                                               sürümü, JDK ve heap ile kayıtlıdır
belirgin kötüleşme raporlanır, test düşmez     süreç kuralı (aşağıda)
```

Hepsi karşılandığı için **İş 9 TAMAMLANDI**; yeni performans testi veya
optimizasyon yazılmadı.

**Kötüleşme raporlama kuralı.** Bir dilim bu testi koşarsa `PERF` satırlarını
yukarıdaki kayıtla karşılaştırır. Aynı yöntem (Gradle test görevi, ısınmamış
JVM) ve benzer ortamda bir ölçüm kaydın iki katını aşarsa bu dilim raporunda
söylenir ve sebebi araştırılır; test bu yüzden düşürülmez ve bir süre eşiği
eklenmez. Farklı donanımda alınan ölçüm yalnız yeni bir kayıttır.

---

# 30. REGRESYON KOMUTLARI

```bash
./gradlew clean check --rerun-tasks
./gradlew run                       # geçici XDG ile
git diff --check
git status --short
```

Geliştirme makinesinde (7,8 GiB RAM) varsayılan ayarlı tam koşu iki kez OS
tarafından bellek yüzünden öldürüldü (İş 10 / Dilim 1). O zaman repo ayarlarına
dokunmadan seri ve sınırlı koşulur:

```bash
./gradlew clean check --rerun-tasks --no-daemon --no-parallel --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
```

Tam koşunun başarısı prize, pile veya CPU governor'ına **bağlı değildir**: Dilim
3'te pilde düşen tanılama süreç testi stabilizasyon turunda olay tabanlı yapıldı
(§25.4 "Stabilizasyon turu"). Yavaş makineyi taklit etmek için sistem güç ayarı
DEĞİŞTİRİLMEZ; yalnız kendi Gradle süreci kısıtlanır:

```bash
systemd-run --user --scope -p CPUQuota=25% --quiet -- ./gradlew desktopTest --rerun \
  --no-daemon --no-parallel --max-workers=1 --tests '<sınıf>' …
```

Linux paketleri (§25.5; doğrulamalar ekran ister, `check`'e bağlı değil):

```bash
./gradlew :app:packageLinuxArchive   # app/build/linux/dist/pnp-tracker-<sürüm>-linux-<mimari>.tar.gz
./gradlew :app:verifyLinuxPackage    # arşiv sözleşmesi + yoklama + pencere, depo dışında
./gradlew :app:packageArch           # app/build/arch/dist/pnp-tracker-<sürüm>-1-<mimari>.pkg.tar.zst
./gradlew :app:verifyArchPackage     # paket sözleşmesi + geçici kökte yoklama + pencere + kaldırma
```

Masaüstü smoke (gerçek pencere, geçici XDG, yalnız kendi penceresini kapatır):

```bash
./gradlew desktopWindowSmoke --no-daemon
./gradlew desktopWindowSmoke -PsmokeScenario=damaged-database --no-daemon   # R13, PLAN 14.7.4
```

Claude Code içinde tam koşu arka plan görevi olarak başlatılırsa görev yöneticisi
bellek baskısında onu durdurabilir (İş 10 kapanışında iki kez, test derlemesinde).
O zaman aynı komut `setsid nohup … &` ile ayrılmış çalıştırılır ve log izlenir.

Farklı bir senaryo için elle tur (örneğin yazılamaz bir veri dizini):

```bash
XDG_DATA_HOME=/tmp/<gecici>/data XDG_CONFIG_HOME=/tmp/<gecici>/config \
XDG_STATE_HOME=/tmp/<gecici>/state ./gradlew --no-daemon run
```

UI smoke listesi: açılış, gezinme, tema, klavye, dar pencere, normal kapanış,
arkada process kalmaması.

**Pencere kapatma kuralı:** pencere başlığının bir parçasıyla (`grep PNP`, `-c
<başlık>`, `-a`, `:ACTIVE:`) hiçbir pencereye istek GÖNDERİLMEZ — Dilim 3'te böyle
bir arama kullanıcının başka bir penceresine kapatma isteği gönderdi. Tek yol
`SafeWindowCloser`'dır (desktopTest): tam başlık + `_NET_WM_PID` başlatılan sürecin
ağacında + kapatmadan hemen önce yeni liste ve `xprop -id` ile yeniden doğrulama +
`wmctrl -i -c <doğrulanmış id>`; sıfır/çok aday ya da güvenilmez listede istek
gitmez ve smoke düşer. Elle turda da pencere ya gerçek pencere düğmesiyle ya bu
yardımcıyla kapatılır; `xdotool windowclose` kullanılmaz. Ekran kilitliyken
otomasyon başlatılmaz; masaüstü veya kilit ekranı yakalanmaz; parola alanına tuş
gönderilmez.

---

# 31. GIT KURALLARI

Dilim başında:

```bash
git status --short
git branch --show-current
git rev-parse HEAD
git log -1 --oneline
```

Beklenmeyen durum varsa **değişiklik yapmadan dur**.

- `main` her zaman çalışır durumda kalır.
- Başarılı dilim → **tek atomik commit**, mesajı dilim talimatındaki mesajla aynı.
- Final `git status --short` boş olmalıdır.
- `git checkout --`, `git restore`, `git reset`, `git clean` veya çalışma ağacını
  atan başka hiçbir komut kullanılmaz.
- Önceki commit amend edilmez.
- Commitler tek sorumluluk taşır.

---

# 32. FAZ İLERLEMESİ — GÜNCEL

PLAN.md `## 18. Üç geliştirme fazı` bölümüne göre.

## Faz 1 — TAMAMLANDI

Temel, veri modeli ve içe aktarma çekirdeği. PLAN `18.` Faz 1 bunu kendi metninde
doğrular: *"Tarihsel not: Faz 1 tamamlanmıştır."*

## Faz 2 — TAMAMLANDI

Oyun tablosu, inline görevler ve üretim havuzları. Bağlayıcı sıradaki 18 işin tamamı
ve yardımcı işler uygulanmıştır:

```text
1-3   PLAN sabitleme, gerçek DB doluluk ölçümü, renk arşivleme temizliği
4     Şema v4 — tablo/hücre/segment modeli, items düşürme, ALTERNATIVE söküm
5     Şema v5 — tamamlanma, eksik sayaçları, aşamalar, ilerleme olayları
6     Oyun tablosu + üç global görünüm
7     Hücrede düz metin yazma
8     Metni tek renk göreve dönüştürme
9     Inline TaskSegment + kelimeye çapalı popover
10    Çoklu görev toplu oluşturma
11    Tek öge çok renk + grapheme bölmeli çizim
12    Basit renk seçici + isimli özel renk
13    Renk düzenleme, silme, temel renkleri geri yükleme
14    Havuz yansımaları (dört havuzun salt okunur görünümleri)
15    Görev tamamlanması ve eksik parça
16    Kart ve mukavva aşamaları
17    Oyun toplu tamamlama ve eksik parçada yeniden açılma
18    Excel verisini hücre/segment modeline onaylı dönüştürme (şema v6)

yardımcı işler
      MISSING / BORROWED / NEEDS_INFO / NEEDS_CLASSIFICATION bayrakları
      arama, renk, durum ve havuz filtreleri
      tamamlananın aktif havuzdan çıkması
      Özel havuzun koşullu gizlenmesi
      hata/eksik geçmişi ve isteğe bağlı not
      CSV içe aktarma
      yapılandırılmış görev CSV dışa aktarma
```

## Faz 3 — BAŞLADI, 16 İŞTEN 12'Sİ BİTTİ

PLAN `18.` — Faz 3 işler listesi.

```text
 1  Geçmiş ekranını tamamla ............................. TAMAM
 2  Import batch rollback ve korumalı geri alma ......... TAMAM (üç dilim)
 3  Sürümlü JSON yedek/dışa aktarma ve geri yükleme ..... TAMAM (dört dilim)
 4  Import ve migration öncesi otomatik snapshot ........ TAMAM (dört dilim, §25.2)
 5  CSV görev dışa aktarmayı doğrula ............... TAMAM (matris §25; bir
                                              kusur düzeltildi: COULD_NOT_READ)
 6  Veritabanı migration testlerini oluştur ............. TAMAM
 7  Beklenmeyen kapanış / bozuk import kurtarma ......... TAMAM (dört dilim, §25.3)
                                                        (PLAN sırası: 7 → 9+5 → 10)
 8  Klavye, odak, renk dışı etiket, yüksek DPI ....... TAMAM (§17 tarama; üç
                                              kusur düzeltildi)
 9  Büyük veri setiyle performans testi ......... TAMAM (1.203 görev, §29;
                                              makineden bağımsız kabul ölçütleri,
                                              süre/bellek eşik değil kayıt)
10  Loglama ve anlaşılır hata mesajları ......... TAMAM (PLAN 14.7, §25.4; sekiz
                                              dilim + stabilizasyon turu: kayıt dosyası,
                                              tipli sınırlar, tipsiz kaçışlar, R12 saat,
                                              R14 ölçüm + geri alma + geri yükleme
                                              kapıları, R13 quick_check açılış kapısı)
11  Self-contained Linux dağıtımı ....................... TAMAM (§25.5; belirlenimci tar.gz,
                                              jlink runtime, verifyLinuxPackage)
12  Garuda/Arch paketi .................................. TAMAM (§25.5; .pkg.tar.zst,
                                              /opt + /usr/bin, verifyArchPackage)
13  Temiz Garuda ortamında kurulum testi ................ YAPILMADI
14  README, kullanıcı kılavuzu, katkı yönergeleri ....... YAPILMADI
15  Açık kaynak lisansı + LICENSE ....................... YAPILMADI
16  GitHub Actions ...................................... YAPILMADI
```

### İş 1'in durumu — iki dilim, ikisi de bitti

**Birinci dilim — olay kayıt katmanı (`3c3c8d1`):** `history_events` tablosu,
`HistoryEventKind`, `Migration6To7` + backfill, ve §15'te sayılan bütün üretim
yollarının olay yazması.

**İkinci dilim — geçmiş ekranı (`feat(history): show what has happened`):**

- `Screen.History`, PLAN 12.1'in sırasında İçe Aktarma ile Renkler arasında.
- `HistoryDao.observeHistoryEvents()` + `observeProgressHistory()` — iki gözlenen
  okuma, isimler JOIN ile, en yeni üstte, eşitliği kimlik çözer.
- `HistoryStore` ikisini tek `HistoryLog` olarak birleştirir; `HistoryController`
  süzgeçleri bellekte uygular. Ekran salt okunur: yazan hiçbir yol yoktur.
- Şema değişmedi; v7 yeterli oldu.

### Geçmiş ekranının gösterdiği satır türleri

PLAN 12.15'in altı maddesinden gösterilebilen hepsi gösteriliyor:

```text
Tamamlanan görevler ............ TASK_COMPLETED
3D eksik/hatalı kayıtları ...... FAILURE_REPORTED   (progress_events)
Eksik giderme hareketleri ...... SHORTAGE_RESOLVED  (progress_events)
Kart/mukavva aşama değişimi .... TASK_STAGE_QUANTITY_CHANGED
Silinen kayıtlar ............... TASK_DELETED, GAME_DELETED
Metne dönüştürülen görevler .... TASK_CONVERTED_TO_TEXT
Yeniden açılan görevler ........ TASK_REOPENED
İçe aktarma onaylandı .......... IMPORT_CONFIRMED
İçe aktarma geri alındı ........ IMPORT_ROLLED_BACK
Geri alınan görevler ........... TASK_ROLLED_BACK
```

`TASK_RESTORED` ve `GAME_RESTORED` hâlâ yazılmıyor (§15, "yazıcısı olmayan
türler"). Ekran bu ikisini **uydurmuyor**; yazan yol geldiğinde kendiliğinden
görünürler, çünkü metinleri ve eşlemeleri hazır.

### Süzgeçler ve sıralama

- Sıra: `occurred_at DESC`, eşitliği `id DESC` çözer. Tek transaction'ın yazdığı
  satırlar aynı anı taşır, aralarında **gerçek bir sıra yoktur** — kimlik yalnız
  tekrarlanabilirlik içindir; ekranda o sıraya anlam yüklenmez.
- Oyun süzgeci: geçmişin adını andığı oyunlar, Türkçe katlamayla sıralı.
- Tarih süzgeci: `Tüm zamanlar`, `Son 7 gün`, `Son 30 gün`. Pencere **şu andan**
  geriye ölçülür ve uzak ucu **dahildir** — tam yedi gün önceki satır içeridedir,
  bir milisaniye öncesi değildir.
- Süzgeçler bellekte, tek okumanın üstünde çalışır: fikir değiştirmek yeni sorgu
  açmaz (PLAN 16).

## Sıradaki bağlayıcı iş

> **Sıradaki bağlayıcı iş: Faz 3 / İş 13 — temiz Garuda ortamında kurulum,
> açılış, veri dizini, güncelleme ve kaldırma testi** (PLAN `18.`; §33 R6: bu
> makinede yapılamaz, ayrı temiz ortam gerekir). İş 13'e bu turda geçilmedi.
>
> **İş 11 ve İş 12 TAMAMLANDI** (§25.5): Java'sız, belirlenimci
> `pnp-tracker-0.1.0-linux-x86_64.tar.gz` ve onu paketleyen
> `pnp-tracker-0.1.0-1-x86_64.pkg.tar.zst`; sürüm tek kaynaktan (Gradle
> `project.version`); ikisi de gerçek başlatıcılarıyla depo dışından çalıştırıldı.
>
> **İş 10 TAMAMLANDI** (§25.4 "İş 10 / Dilim 4–8'de uygulanan hâli"): R12 saat
> geriye gidince yedekler kullanılabilir; R14'ün 13 adayı ölçüldü, geri alma
> C2–C4'te `PROVENANCE_BROKEN`, geri yükleme `L`'de onaydan önce
> `IMPORT_RECORDS_CONTRADICT`; R13 hasarlı DB Room'dan önce `quick_check` ile
> `DATABASE_DAMAGED`. Tam koşu 3701 / 0 / 0 / 0 (269 sınıf).
>
> Stabilizasyon turu bitti (`test(desktop): make process and window checks
> deterministic`, üretim kodu değişmedi): tanılama testleri olay tabanlı, tam koşu
> prize bağlı değil; masaüstü smoke yalnız `SafeWindowCloser` ile kendi penceresini
> kapatıyor; üç okuma yolunun IllegalArgument ve Error sınırları testli (§25.4
> "Stabilizasyon turu"). Dilim 4'e bu turda geçilmedi.
>
> Dilim 3 bitti: Dilim 2'nin ölçtüğü beş tipsiz kaçışın beşi de tipli Türkçe
> sonuca çevrildi (§25.4 "İş 10 / Dilim 3'te uygulanan hâli"). Yeni olay kodu
> eklenmedi; üç gözlenen okuma tek bir dar seam'den (`answeringStorageRefusal`)
> geçiyor, yalnız `SQLiteException` cevaplanıyor, kusur ve cancellation aynen
> yükseliyor, ve reddedilen bir okuma asla boş liste olarak çizilmiyor. Açılış
> dizini hatası artık `StartupProblem.FOLDERS_NOT_CREATED`; mesajındaki mutlak
> yol kaldırıldı. Dilim 2 zaten bitmişti: her tipli sınır kendi hatasını bir kez
> kaydediyor, sahiplik `DiagnosticOwnershipTest` ile çivili, kayıt hiçbir
> transaction'ın içinde verilmiyor ve başarılı bir tur hiç satır yazmıyor.
>
> **İş 5 TAMAMLANDI (§25 matris). İş 8 TAMAMLANDI (§17). İş 9 TAMAMLANDI (§29).
> İş 10'un bütün kararları verildi (PLAN `14.7`)** — R12, R13 ve R14 dahil; kod
> dilimleri 1 → 8 sırasıyla yapılır. İş 11 (paket türü, JRE, sürüm kaynağı R5),
> İş 12 (Arch dağıtım biçimi), İş 13 (temiz VM, R6), İş 15 (lisans) ve İş 16
> (remote/CI) kullanıcı kararı bekler. İş 14'ün paketlemeden bağımsız kısmı karar
> gerektirmeden yapılabilir. Aşağıdaki not İş 7'nin kapanış özetidir.
>
> İş 7'nin sözleşmesi yazıldı (PLAN `11.4.5`, `16.`, `17.`, `18.`; özet, repo
> denetimi ve uygulanan hâl §25.3). Kararlar verilmiştir ve yeniden
> tartışılmaz. Dilim 1 bitti: dört yazma yolu gerçek süreç öldürmesiyle ölçüldü,
> kalıcılık ayarı `wal` + `synchronous = NORMAL` olarak kayda geçti. Dilim 2
> bitti: `ImportDao.removeDraftBatch` + `ImportDraftRemovalStore` — tek
> transaction, beş tipli sonuç, 15 tablo sayımıyla postcondition, taslak
> boyutundan bağımsız altı ifade; arayüze bağlı değil. Dilim 3 bitti: D1–D9'un
> dokuzu da gerçek restore hattından canlı DB'ye ulaştı (ölçüldü, PLAN değişmedi);
> salt okunur `ImportDao.draftHealthOf` üç okumayla sınıflandırıyor; onay bozuk
> taslağı snapshot'tan önce ve transaction içinde `RECORDS_CONTRADICT_EACH_OTHER`
> ile reddediyor. Dilim 4 bu ikisini ekrana bağlar: geçerli taslakta Aç + Kaldır,
> bozuk taslaklar ayrı bölümde yalnız Kaldır; bütün taslakları sınıflandırırken
> batch başına üç okumayı N kez yapmamalıdır (§25.3 Dilim 3 "YAPMADIKLARI").
>
> ```text
> 1  kesintiye dayanıklılığın kalıcı kanıtı (yalnız test) ...... TAMAM
> 2  taslağı kaldırma motoru (arayüz yok) ....................... TAMAM
> 3  bozuk DRAFT sınıflandırması + onay kapısı .................. TAMAM
> 4  arayüz: devam et / kaldır / bozuk taslak uyarısı ........... TAMAM
> ```
>
> Hiçbir dilim Room şemasını değiştirmez. Dilim 4'ün (İş 4) kilidi, sıcak WAL
> okuması, migration kapısı ve açılış hata ekranı yeniden yazılmaz.
>
> PLAN'ın kendi sırası İş 7'den sonra şudur: **İş 9 + İş 5 → İş 10 →
> İş 11-13 → İş 14-16.**
>
> Riskler: §33 R12, R13 ve R14'ün kararları İş 10 belge turunda verildi (PLAN
> `14.7.3`–`14.7.5`) ve kodda henüz uygulanmadı; R15 (instance kilidi yalnız
> açılış boyunca) yeni bir gözlemdir.

### İş 3'ün dört atomik dilimi — dördü de bitti

```text
1  JSON sözleşmesi, bütün DB snapshot'ı ve deterministik yazıcı ..... TAMAM
2  Manuel yedek dosyası yazma + Ayarlar ekranı ...................... TAMAM
3  Güvenilmeyen dosyayı parse etme, doğrulama, geçici DB denemesi ... TAMAM
4  Güvenlik yedeği + canlı replace transaction + arayüz ............. TAMAM
```

### Kullanıcının bugün görebildiği geri yükleme akışı

```text
Ayarlar → Yedekleme
  Yedek oluştur ....... hedef seçilir, gerekiyorsa üzerine yazma onayı, atomik yazma
  Yedekten geri yükle . .json seçilir → "Yedek denetleniyor…" → dosya geçemezse
                        Türkçe ret cümlesi ve YIKICI ONAY GÖSTERİLMEZ
                      → geçerse özet (ad, alınma zamanı, oyun/görev/renk sayısı)
                        ve "Bu yedek geri yüklensin mi?" — odak `Vazgeç`te
                      → `Geri yükle` → "Güvenlik yedeği yazılıyor…" →
                        "Yedek geri yükleniyor…" → başarı, iki dosya adıyla
                      → ekranlar yeniden başlatmadan yeni veriyi gösterir
```

### İş 2'nin üç atomik dilimi — üçü de bitti

```text
1  Room v8 ve hücre anlık görüntüsü ....................... TAMAM
   import_batch_cells, Migration7To8, 8.json, onayın document_before yazması.
   Davranış değişmedi; kullanıcı hiçbir fark görmedi.

2  Rollback motoru ve geçmiş olayları ..................... TAMAM
   Engelleme denetimi (dokunulmuş görev VEYA değişmiş hücre), tek transaction,
   tombstone'lar, hücre geri yüklemesi, postcondition;
   IMPORT_CONFIRMED / IMPORT_ROLLED_BACK / TASK_ROLLED_BACK ve Türkçe geçmiş
   cümleleri. Geri almayı başlatan arayüz henüz yoktu.

3  Arayüz ................................................ TAMAM
   Onaylanmış içe aktarma listesi, CONFIRMED satırlarda `Geri al`, önizlemeye
   dayanan onay penceresi, engelleme penceresi, başarı bildirimi.
   ImportRollbackStore Main.kt'ye bağlandı. Ayrıntılar §19'da.
```

Sıra bağlayıcıydı: 1 olmadan 2 tam hücre geri yüklemesi yapamazdı, 2 olmadan
3'ün çağıracağı bir şey olmazdı.

### Dilim 3'ün kapattığı iki kanıt boşluğu

Dilim 2 sonrası salt okunur denetim, davranışı doğru ama testi eksik iki noktayı
kayda geçirmişti. İkisi de bu dilimde gerçek Room testiyle kapandı:

```text
oyun tamamlanma bayrağı    ImportRollbackTest
                           "the games the import finished stay finished"
v7'den migrate edilmiş,    ImportRollbackLegacyBatchTest (CommittedSchema ile
snapshot'sız CONFIRMED     kurulan gerçek v7 DB, gerçek Migration7To8):
batch                      NO_CELL_SNAPSHOT + bütün tabloların satır sayısı aynı
```

### Kullanıcının bugün görebildiği geri alma akışı

```text
İçe Aktarma → Onaylanmış içe aktarmalar → [Geri al]
   → önizleme okunur (yükleniyor durumu, düğme kapalı)
   → güvenliyse onay penceresi: kaç görev, kaç hücre, kaç oyun,
     tamamlanma işaretlerinin kalacağı, geri alınamazlık
   → [Geri al] → tek transaction → "İçe aktarma geri alındı"
   → satır kendiliğinden `Geri alındı` olur ve düğmesi kalkar
   → Geçmiş ekranında oyun başına ve görev başına satırlar görünür

engellenirse: neyin engellediği (görev adı + neden, oyun + sütun + neden),
onay düğmesi YOK, yalnız Kapat.
```

## v7 öncesi kurtarılamayan geçmiş

`Migration6To7` yalnız v6'nın gerçekten bildiği üç olguyu kurtarır
(`tasks.completed_at`, `tasks.deleted_at`, `games.deleted_at`). Kurtarılamayanlar,
uydurulmadıkları için açıkça kayıtlıdır:

- Eski aşama hareketleri — v6 nerede durulduğunu tutar, nasıl gelindiğini değil.
- Eski yeniden açmalar — yeniden açma `completed_at`'i temizler.
- v7 öncesi metne dönüştürmelerde fiziksel silinmiş görev/renk/aşama/ilerleme kayıtları.
- Hiç yapılmamış import geri almaları.

Geçmiş ekranı, v7 öncesi dönem için yalnız tamamlanma ve silme satırları
gösterir. Bu bir eksiklik değil kayıt sınırıdır ve ekran bunu doldurmaz.

Adlar da anlık görüntü değildir: bir satır, oyununu ve görevini **bugünkü**
adlarıyla gösterir, çünkü kullanıcının arayacağı ad odur ve PLAN tarihsel ad
yazmaz. Silinen ve metne dönüştürülen kayıtlar tombstone olduğu için adları
durur; metne dönüştürülmüş bir görevin eski eksik bildirimleri hücre parçasını
kaybettiğinden oyunlarını `history_events`'teki dönüştürme satırından alır.

---

# 33. RİSKLER VE VERİLMİŞ TASARIM KARARLARI

## R1 — Rollback provenance  *(KAPANDI — tasarım uygulandı ve kullanılıyor)*

> Bu maddenin önceki hâli `cell_segments.source_import_batch_id` sütunu öneriyordu.
> **O öneri geri çekilmiştir.** Aşağıdaki inceleme bulgusu onu geçersiz kılar.
> Bağlayıcı kurallar artık `PLAN.md` `11.4.4`'tedir.

### Bugünkü provenance durumu  *(v7 üzerinde doğrulandı)*

`ImportDao.kt:1090–1135`'te dört batch-kapsamlı sorgu zaten var ve çalışıyor:

```text
tasks         ✓  source_raw_import_block_id  VE  draft_tasks.materialized_task_id
task_colors   ✓  görev üzerinden
task_stages   ✓  görev üzerinden
TASK segment  ✓  cell_segments.task_id → draft_tasks (UNIQUE index)
ayırıcı PLAIN_TEXT segment   ✗  batch'e bağlanamaz
oyun tamamlanma bayrağının çevrilmesi   ✗  iz yok
games satırı  —  konu dışı: import oyun oluşturmaz (GameSetupStore.kt:93 null yazar)
```

Yani **v7 minimal bir rollback yapabilir**: görevleri, renkleri, aşamaları ve
TASK segmentlerini batch'e bağlamak için şema değişikliği gerekmez.

### Neden segment kimliğine provenance bağlanmıyor

`CellSegmentDao.rewrite` (CellSegmentDao.kt:270–313) kullanıcı bir hücreyi her
düzenlediğinde yan yana düz metin parçalarını birleştirir, birleşen aralığın
metnini **ilk satırın kimliğine** yazar ve kalanları siler. Sonuç iki türlü de
kötüdür:

- `[kullanıcı metni][ayırıcı][GÖREV]` düzeninde ilk düzenlemede **ayırıcı satırı
  yok olur**; etiket de onunla gider.
- `[GÖREV1][ayırıcı][GÖREV2]` düzeninde ayırıcı kimliğini korur ve kullanıcı araya
  yazdığında **o metni kendi satırına alır**. Rollback o satırı silerse kullanıcı
  metnini siler.

Kimliğe bağlı provenance, kimliğin altındaki metin değişebiliyorsa güvenli
değildir. Bu yüzden dayanak satır kimliği değil, **saklanan metnin kendisidir**.

### Uygulanan tasarım — Room v8  *(bu commit'te yazıldı)*

Seçilen ürün tasarımı güvenli ve **tam** hücre geri yüklemesi istiyor; bunun için
v8 gerekliydi. Mevcut hiçbir tablo, sütun veya index değişmedi; tek ekleme:

```text
import_batch_cells
  import_batch_id  FK → import_batches(id)  ON DELETE CASCADE
  cell_id          FK → game_cells(id)      ON DELETE RESTRICT
  document_before  hücrenin onay anındaki tam metni
  PRIMARY KEY (import_batch_id, cell_id)
  INDEX (cell_id)
```

`document_after` saklanmaz: beklenen metin, `document_before` + görev adları +
`taskNeedsSeparatorAfter` (TaskSeparation.kt:33) ile yeniden hesaplanır — onayın
kullandığı saf fonksiyonun ta kendisi, böylece iki hesap ayrışamaz.

`Migration7To8` **backfill yapmaz.** Eski onaylar hücre metnini saklamamıştır;
bugünkü metinden geriye çıkarmak uydurma olurdu (`Migration6To7`'nin ilkesi).
Anlık görüntüsü olmayan batch geri alınamaz ve sebebi kullanıcıya söylenir.

Dosyalar:

```text
ImportBatchCellEntity.kt          entity, bileşik PK, iki FK, cell_id indeksi
Migration7To8.kt                  yalnız CREATE TABLE + CREATE INDEX
8.json                            Room'un ürettiği şema; migration'ın SQL'i ile
                                  harfi harfine aynı (Migration7To8Test kanıtlar)
ImportDao.confirmDraftBatch       ilk domain yazımından önce anlık görüntüyü yazar
ImportDao.cellSnapshotsOfBatch    dilim 2'nin okuyacağı dar okuma
```

`cell_segments` **değişmedi** ve provenance sütunu almadı — bu maddenin bütün
gerekçesi buydu.

## R2 — Rollback ürün kararları  *(KAPANDI — PLAN 11.4.4 uygulandı)*

Bu maddenin önceki hâli sekiz açık soru listeliyordu. **Hepsi kullanıcı tarafından
karara bağlanmıştır** ve `PLAN.md` `11.4.4`'e işlenmiştir. Burada yalnız repo
tarafındaki sonuçları kayıtlıdır; kural metni PLAN'dadır ve çelişkide PLAN kazanır.

```text
Engelleme         Tek bir dokunulmuş görev VEYA değişmiş hedef hücre bütün
                  geri almayı durdurur. Kısmi rollback YOKTUR.
Dokunulmuşluk     updated_at != created_at | ProgressEvent var | HistoryEvent var
                  | zaten silinmiş/metne dönüştürülmüş     (dördü de engelleyici)
Durum             ROLLED_BACK ancak bütün hedefler güvenliyse ve hepsi tek
                  transaction'da kaldırıldıysa yazılır; aksi hâlde CONFIRMED kalır.
                  Dördüncü bir ara durum eklenmez.
Silme             tombstone; progress ve history olayları fiziksel silinmez.
Oyun bayrağı      import'un çevirdiği tamamlanma işareti geri alınmaz.
İkinci çağrı      korumalı reddediş, sessiz başarı değil.
Yeniden uygulama  yok; aynı dosya yeni batch olarak alınır, parmak izi uyarısı durur.
Geçmiş            IMPORT_CONFIRMED / IMPORT_ROLLED_BACK (oyun bazlı) +
                  TASK_ROLLED_BACK (görev bazlı). Engellenen geri alma olay yazmaz.
```

Kararların bilinen sert kenarı, bilerek kabul edilmiştir: bir batch'in tek bir
görevi silinmiş veya tek bir hedef hücresinin metni düzenlenmişse o batch artık
geri alınamaz. Alternatifi kullanıcının kararlarının üzerine yazmaktır.

Uygulama sırasında doğrulanmış iki dayanak: import `updated_at` ile `created_at`
değerlerini eşit yazar (ImportDao.kt:1499–1500) ve `TaskEditDao.editTask` no-op'ta
hiç yazmaz (TaskEditDao.kt:312) — bu ikisi olmadan "dokunulmuşluk" ölçütü
güvenilir olmazdı.

## R3 — Kullanılmayan üretim API'leri  *(düşük — bir kısmı kapandı)*

`ImportRollbackStore` artık `Main.kt`'ye bağlıdır ve İçe Aktarma ekranından
çağrılır; dilim 2'de bilerek bağlanmamış olması bu maddenin sebebiydi ve o kısım
**kapanmıştır**. Aynı kalıbın ikinci örneği de kapandı: Dilim 3'ün bir commit
boyunca çağrılmayan okuma hattı (`UntrustedBackupReader`, `PathBackupInput`,
`TemporaryBackupProbe`) Dilim 4'te `RestoreController` üzerinden bağlanmıştır.

İş 7 / Dilim 2 aynı kalıbı bilerek bir kez daha kullanmıştı
(`ImportDraftRemovalStore` bağlı değildi); **Dilim 4 bunu kapattı**: store
`UnfinishedImportsStore` üzerinden `Main.kt`'ye bağlı ve kullanıcı taslağı
kaldırabiliyor. Dilim 4'ten sonra `ImportReview.observeDraftBatches()` (store
arayüzü) üretimde çağıranını kaybetti; kendi store testiyle yerinde bırakıldı.

İş 7 / Dilim 3 bu kalıbı **kullanmadı**: `ImportDao.draftHealthOf`,
`Main`'e zaten bağlı olan `ImportConfirmationStore`'un iki kapısından
çağrılıyor; sınıflandırma bugün kullanıcının onay yolunda çalışır.

Geriye kalan: `TaskDao.softDelete`, `GameDao.softDelete` ve
`completePrimaryBatch` üretim kodundan çağrılmıyor; `TASK_RESTORED` /
`GAME_RESTORED` yazılmıyor. Yani **uygulamada bugün elle görev/oyun silme ve
geri yükleme yolu yoktur.** Bilinçli bir karardır: sırf olay üretmek için
özellik uydurulmamıştır. Geri alma tombstone'u kendi sorgusuyla yazar
(`writeTaskTombstone`), bu yollara dayanmaz.

## R4 — Soft-delete edilmiş görev + duran segment  *(düşük, bugün ulaşılamaz)*

`GameTableDao.observeCellContents` `tasks.deleted_at`'i filtrelemez; hücre parçası
duran soft-delete edilmiş bir görev oyun tablosunda çizilir. Bu şekli **hiçbir üretim
yolu üretmez** (metne dönüştürme parçayı kaldırır, başka silme yolu yok). İş 2 gerçek
bir silme yolu getirdiğinde kusura dönüşür.

## R5 — Sürüm numarası ikiye ayrılabilir  *(KAPANDI — İş 11, `a942683`)*

Eskiden `AppInfo.kt` içinde `"0.1.0"` elle yazılıydı. Artık tek kaynak Gradle
`project.version`'dır; `AppInfo` üretilen `APPLICATION_VERSION` sabitini okur ve
paket adları, jpackage, PKGBUILD aynı değerden türer (§25.5).

## R6 — Bu makinede doğrulanamayan madde

PLAN `18.` Faz 3 (İş 13 ve testleri) "temiz Garuda ortamında kurulum, açılış, veri dizini, güncelleme ve
kaldırma testi" ayrı bir temiz ortam gerektirir.

## R7 — Paketleme yapılandırması yok  *(KAPANDI — İş 11 ve 12)*

`nativeDistributions` tanımlı; `createDistributable`, `packageLinuxArchive`,
`packageArch` ve iki doğrulama görevi var (§25.5). `targetFormats` bilinçli
olarak boş: deb/rpm/AppImage bu projenin çıktısı değildir. `LICENSE` dosyası
(İş 15) ve `.github/` dizini (İş 16) hâlâ yok.

---

## R8 — Kod içindeki PLAN satır atıfları  *(ÇÖZÜLDÜ — bölüm numarasına geçildi)*

`PLAN.md`'ye `11.4.4` eklenmesi dosyayı 1829 satırdan 2002 satıra çıkarmış ve
ekleme noktasından sonraki bütün satır numaraları kaymıştı. Kaynak ve test
dosyalarındaki **61 atıf / 24 dosya** yanlış satırı gösteriyordu:

```text
eski → yeni    ne olduğu
141  → 142     silinen kayıt hemen fiziksel silinmez
143  → 144     kalıcı fiziksel temizleme ayrı bir bakım işidir
385  → 389     failureTotal olaylardan türetilir ve silinmez
427  → 431     görev oyun kaydında ve geçmişte kalır
437  → 441     tamamlanmış görevde eksik bildirimi
1118 → 1264    geçmiş: tamamlanan görevler
1119 → 1265    geçmiş: 3D eksik/hatalı kayıtları
1120 → 1266    geçmiş: eksik giderme hareketleri
1121 → 1267    geçmiş: aşama değişiklikleri
1123 → 1269    geçmiş: silinen ve metne dönüştürülen kayıtlar
1404 → 1564    migration sessizce veri silmez
1410 → 1570    tamamlanan görev aktif havuzdan çıkar
1503 → 1676    1.000+ görev performansı
```

Satır numarasını tazelemek yerine **bölüm numarasına geçildi**, çünkü bölüm
numaraları PLAN büyüdüğünde kaymaz. 61 atfın hepsi bu commit'te çevrildi:

```text
141, 143     → PLAN 5.2
385          → PLAN 5.12
427, 437     → PLAN 6.3
1118 … 1123  → PLAN 12.15
1404, 1410   → PLAN 18 (Faz 2, Adım 4)
1503         → PLAN 18 (Faz 3 testleri)
```

Yalnız yorum, KDoc ve test açıklamaları değişti; hiçbir üretim davranışı
değişmedi. Aynı bölüme düşen iki atıf yan yana geldiğinde ikincisi "aynı bölümün
anlattığı ayrı bakım işi" gibi metin içinde çözüldü, ikinci kez numara
tekrarlanmadı.

Kural bundan sonra şudur: **PLAN'a satır numarasıyla atıf yapılmaz.** Tarama:

```bash
grep -rnoE 'PLAN [0-9]{3,}' app/src/     # boş dönmelidir
```

---

## R9 — Yabancı anahtar zorlaması  *(KAPANDI — ölçüldü; zorlama AÇIK)*

Şüphe yanlıştı ve düzeltilmiştir. `room3-runtime` 3.0.1 jar'ında
`PRAGMA foreign_keys = ON` bulunmaması zorlamanın kapalı olduğu anlamına gelmiyor:
**bundled SQLite sürücüsü onu kendisi açıyor.**

Ölçüm (`ForeignKeyEnforcementTest`, üretim `DatabaseFactory`'siyle açılan geçici
bir veritabanında):

```text
PRAGMA foreign_keys                                  → 1
olmayan bir oyuna işaret eden game_cells INSERT'ü    → SQLite 787
                                                       "FOREIGN KEY constraint failed"
sonrası game_cells satır sayısı                      → 0
```

Sonuçları:

- **Mevcut veri ve migration'lar için etkisi yok.** Zorlama baştan beri açıkmış;
  yani kopuk bir referans zaten hiçbir zaman yazılamamış, düzeltilecek bir geçmiş
  yok.
- Bu dilimde global davranış **değiştirilmedi**; değiştirilecek bir şey yoktu.
- Geri yükleme tasarımı aynen geçerli ve artık daha gerekçelidir: satırlar doğru
  sırayla yazılır, **temizleme adımında `defer_foreign_keys` gerçekten
  gerekecektir** ve commit'ten önceki açık `foreign_key_check` kararı korunur
  (PLAN 14.4.3).
- Test bundan sonra tersini bekler: zorlama bir bağımlılık yükseltmesiyle sessizce
  kapanırsa haber verir.

---

## R10 — Gerçek veritabanı korumasız bir migration'a açık  *(KAPANDI — kapı yazıldı)*

Gerçek kullanıcı veritabanı şema **v3**'tedir (§0) ve kodun şema sürümü 8'dir,
yani bir sonraki normal açılış gerçek bir v3 → v8 geçişidir. Risk, o geçişin
**snapshot olmadan** çalışabilmesiydi.

Dilim 4 bunu kapattı ve kapatma biçimi hatırlanacak bir kural değil:

```text
önce      DatabaseFactory().open(...) doğrudan çağrılıyordu
şimdi     yalnız StartupGate.open() çağrılır; eski bir şema görülürse gerçek
          açılış, doğrulanmış bir MigrationSnapshotSet'i TUTAN dalın içindedir
          ve o tipin üretimi iki eşin de yazılıp geri okunmasına bağlıdır
kanıt     StartupSurfaceTest: üretimde DatabaseFactory.open'ı çağıran üç yer
          vardır ve üçü de sayılıdır (kapı, çalışma kopyası, geçici prob);
          Main'de doğrudan çağrı YOKTUR
          StartupGateTest: seti yazılamayan / migrate edilemeyen / doğrulanamayan
          her yolda gerçek DB **v3'te kalır**
```

Ayrıca ölçüm sırasında ikinci bir güvence ortaya çıktı: `Migration3To4`, v3'te
**oyun/öge/görev** taşıyan bir veritabanını bilerek reddeder ve bu reddediş bir
depolama hatası değildir. Dilim 4'ten önce böyle bir reddediş ham exception
olarak dışarı çıkardı; artık `SNAPSHOT_NOT_MIGRATED` olarak yakalanıp Türkçe
hata ekranına dönüşüyor ve **asıl veritabanına hiç dokunulmuyor**. Gerçek DB'nin
yalnız 12 tohum renk taşıdığı gözlemi bir garanti değildi; artık garanti
gerekmiyor, çünkü her iki durumda da davranış güvenli.

## R11 — `VACUUM INTO`'nun bu kurulumdaki davranışı  *(KAPANDI — ÖLÇÜLDÜ)*

Migration öncesi ham klon için `VACUUM INTO` kullanılmasına izin verilmişti
(PLAN `14.4.9`) ve gerçek davranışı ölçülmemişti. **Dilim 4'ün ilk işi bu ölçüm
oldu**, ve sonuç bağlayıcı tasarımla uyuşuyor. Ölçümün kendisi kalıcıdır:
`ConsistentDatabaseCloneTest`, 11 test.

```text
salt okunur bağlantı + VACUUM INTO      ÇALIŞIYOR
sıcak WAL, canlı yazıcı varken          klon WAL'daki satırları TAŞIYOR
kaza kopyası (-wal var, -shm var)       ÇALIŞIYOR
kaza kopyası (-wal var, -shm YOK)       ÇALIŞIYOR
user_version                            KORUNUYOR (hem başlık baytı hem PRAGMA)
integrity_check                         ok
migration kodu                          HİÇ ÇALIŞMIYOR (klon v3 kalır; v7/v8
                                        tabloları görünmez, `items` durur)
kaynak .db ve -wal                      BAYT BAYT DEĞİŞMİYOR
kaynak -shm                             okuyucu tarafından oluşturulabilir/
                                        güncellenebilir — veri taşımaz
var olan hedef dosya                    REDDEDİLİR (üzerine asla yazılmaz)
veritabanı olmayan dosya                REDDEDİLİR
```

**Tasarımı belirleyen negatif sonuç:** aynı kaza kopyası **okuma-yazma** açılıp
kapatıldığında kaynak değişiyor — WAL checkpoint edilip siliniyor ve veritabanı
dosyası yeniden yazılıyor. Yani "dikkatli davranmak" yetmez; bağlantının salt
okunur olması zorunludur. Bu da kalıcı bir testtir.

`SQLITE_OPEN_NOFOLLOW` bilinçli olarak istenmiyor: Room aynı dosyayı onsuz
açıyor, dolayısıyla burada sembolik bağı reddetmek uygulamanın geri kalanının
kabul ettiği bir kurulumu reddetmek olurdu.

Statik olarak doğrulanmış olanlar (Dilim 4 öncesinden, hâlâ geçerli):

```text
gömülü SQLite      3.50.1  → VACUUM INTO (3.27+) sürüm olarak mevcut
OMIT_VACUUM        derleme seçeneklerinde YOK
erişim yolu        sıradan SQL → SQLiteConnection.prepare()/step() ile ulaşılır
sqlite3_backup_*   semboller .so içinde var ama JNI'ye BAĞLANMAMIŞ → ERİŞİLEMEZ
sqlite3_serialize  iz yok → ERİŞİLEMEZ
açılış bayrakları  SQLITE_OPEN_READONLY ve SQLITE_OPEN_NOFOLLOW mevcut
```

Endişe edilen `SQLITE_READONLY_RECOVERY` durumu bu kurulumda **gerçekleşmedi**:
sıcak WAL taşıyan bir veritabanı salt okunur açıldı ve klonlandı, `-shm` olsa da
olmasa da. Varsayılmadı, ölçüldü.

Ayrıca **yasak olan**, hiçbir koşulda denenmeyecek alternatif: veritabanı, `-wal`
ve `-shm` dosyalarını sırayla kopyalamak. Üçü arasında atomiklik yoktur ve
kopyalama sırasında araya giren bir checkpoint tutarsız bir üçlü bırakır.

---

## R12 — Geriye giden bir saat içe aktarmayı tamamen durdurur  *(KAPANDI — PLAN 14.7.3; İş 10 / Dilim 4, `efd6f3a`)*

Bulgunun kendisi (İş 4 / Dilim 3 smoke'u): yedek okuyucusu `updated_at < created_at`
olan bir satırı **DOMAIN_INVARIANT** ile reddeder (`BackupValues.checkWrittenAndChanged`;
`import_batches` için `updatedAt < importedAt`). Otomatik snapshot doğrulamayı
geçemez → PLAN 14.4.13 fail closed → kullanıcı hiçbir içe aktarmayı onaylayamaz.
Ölçüm: `BackupRejection(DOMAIN_INVARIANT, rawImportBlocks.updatedAt)`.

**Karar:** zaman sırası bütünlük kuralı değildir; doğrulama yolları onu reddetmez,
epoch değerleri aynen korunur, hiçbir yol düzeltme yapmaz. Ayrıntı, sıralama
denetimi ve "kötü hazırlanmış yedeğe verilen ek serbestlik" analizi PLAN
`14.7.3` ve §25.4'tedir.

İş 10 belge turunun kod okumasıyla bulduğu **ikinci etki** (ölçülmedi):
`AutomaticBackupRotation.surplusOf` türünü ad damgasına göre sıralayıp en yeni
`keep` kaydı tutar ve `justWritten`'ı yalnız **var olduğunu** denetler. Saat
geri gittiğinde yeni yedeğin damgası eskilerinkinden önce sıralanır; kendi
türünde en az `keep` eski yedek varsa **az önce yazılıp doğrulanan yedek
silinir** ve içe aktarma onayı yine de sürer. PLAN `14.4.11` artık bunu yasaklar;
düzeltme Dilim 4'tedir.

**Uygulandı (`efd6f3a`):** okuyucu ve `EntityTimestamps` zaman sırasını
reddetmiyor, epoch aynen korunuyor, eşitlikler `id` ile çözülüyor, rotation az
önce yazılanı hiçbir zaman fazlalık saymıyor (keep 1..5 testli). Açık kalan yok.

## R13 — Veritabanının geneli `integrity_check`'ten geçmezse  *(TESPİT KAPANDI — PLAN 14.7.4; İş 10 / Dilim 8, `34ef740`; kurtarma bilinçli olarak açık)*

Bugün açılış kapısı canlı veritabanında bütünlük denetimi çalıştırmaz; yalnız ham
migration klonunda `integrity_check` + `foreign_key_check` çalışır
(`ConsistentDatabaseClone.isWholeAndConsistent`). Sayfa düzeyinde hasarlı bir
canlı veritabanının davranışı ölçülmemiştir.

**Karar:** her açılışta, Room'dan önce ve salt okunur bağlantıda
`PRAGMA quick_check`; geçmezse `StartupProblem.DATABASE_DAMAGED`, veritabanı
açılmaz, set/rotation çalışmaz, hiçbir dosya değişmez. Onarma, taşıma, silme,
takas ve uygulama içi kurtarma **yoktur**; mevcut "DB/WAL/SHM takası yok" ve
transaction tabanlı restore sözleşmesiyle güvenli bir kurtarma kanıtlanamadığı
için sınır "tespit et, yazmayı engelle, veriyi koru, ayrı kurtarma tasarımı
gerektir"dir (kanıt PLAN `14.7.4`). Açık kalanlar: maliyet ve tespit kapsamı
Dilim 8'de ölçülecek; indeks-içerik uyuşmazlığı açılışta aranmaz; oturum
sırasında oluşan hasar ayrıca sınıflandırılmaz; elle kurtarma belgesi İş 14 +
ayrı karar.

**Uygulandı (`34ef740`, smoke `a588d0e`):** ölçümde `quick_check` dört hasar
sınıfının dördünü buldu (1.203 görevde ≈ 1,5 ms, 12.030'da ≈ 12 ms); kapı sürüm
1..8'de Room'dan önce çalışıyor, kusurları maskelemiyor ve var olan yan
dosyalara dokunmayan bir salt okunur bağlantı kullanıyor (§25.4). Bilinçli
olarak açık: kurtarma (İş 14 + ayrı karar), indeks-içerik uyuşmazlığı, oturum
içi hasar sınıfı, `-shm`'siz sıcak WAL'da SQLite'ın oluşturduğu `-shm`.

## R14 — Yedek okuyucusu import yaşam döngüsünü denetlemiyor  *(KAPANDI — PLAN 14.7.5; İş 10 / Dilim 5–7, `feadbf8`, `e5f73f7`, `966b546`)*

Bulgu ve İş 7 ölçümü aynen geçerlidir: `BackupValues` ve `BackupGraph` batch
durumunu çocuk satırlarla karşılaştırmaz; `D1`–`D9`'un dokuzu da gerçek restore
hattından canlı Room 8 veritabanına ulaşır (§25.3 matris).

**Karar:** okuyucu **sıkılaştırılmaz**, çünkü aynı okuyucu içe aktarma
snapshot'ını ve migration setini de doğrular; canlı DB zaten çelişki taşıyorsa
bütün onayları ve ileride bir migration'da açılışı kilitlerdi, ve `D6`/`D7`,
`CONFIRMED`, `ROLLED_BACK` çelişkileri için uygulama içi çözüm yolu yoktur.
Bunun yerine:

```text
Dilim 5  CONFIRMED/ROLLED_BACK aday invariant'ları (C1–C5, RB1–RB5, U1–U3) ölçülür
         → kesin küme L
Dilim 6  geri alma, L ∩ {C2, C3, C4} bozuksa PROVENANCE_BROKEN (0 ek SQL)
Dilim 7  kullanıcının seçtiği yedek, okuyucudan SONRA ve onay sorusundan ÖNCE,
         bellekte L ile denetlenir → BackupProblem.IMPORT_RECORDS_CONTRADICT
```

Kod okumasıyla bulunan zarar vektörü (ölçülmedi, Dilim 5 gözler):
`planImportRollback` görevin çapasını ve dokunulmuşluğunu denetler, fakat
`task(d).source_raw_import_block_id`'nin taslağın bloğu olduğunu (C3) ve
taslakların hedef hücreleriyle `import_batch_cells`'in eşleştiğini (C4)
**denetlemez**. Elle hazırlanmış bir belge, bir `CONFIRMED` batch'in taslağını
kullanıcının elle yazdığı, dokunulmamış bir göreve bağlayabilir; geri alma o
görevi tombstone'lar.

Bilinen ve kabul edilen sonuç: canlı DB `L`'den bir çelişki taşırken alınmış bir
yedek geri yükleme kapısında reddedilir (PLAN `14.4.7`); canlı veri korunur.

**Uygulandı:** Dilim 5 (`feadbf8`) 13 adayın 13'ünün canlı DB'ye ulaştığını,
uygulamanın kendi yollarının ve şema 3–7 verisinin hiçbirini bozmadığını ve
C3/C4'ün geri almada gerçek zarar verdiğini ölçtü → `L` = D1–D9 ∪ C1–C5 ∪
RB1–RB5 ∪ U1–U3. Dilim 6 (`e5f73f7`) geri almayı C2–C4'te `PROVENANCE_BROKEN`
ile durduruyor (0 ek SQL). Dilim 7 (`966b546`) kullanıcının seçtiği yedeği
onay sorusundan ve güvenlik yedeğinden önce `IMPORT_RECORDS_CONTRADICT` ile
reddediyor; D1–D9'un tek tanımı `domain/importhealth`. Bilinçli olarak açık:
canlı DB'deki çelişkilerin uygulama içi çözümü yok.

## R15 — Instance kilidi yalnız açılış boyunca tutulur  *(GÖZLEM — İş 10 belge turunda kod okumasıyla bulundu; İş 10 için karar gerekmez)*

`StartupGate.open()` kilidi `lock.withLock { openUnderTheLock() }` ile alır ve
açılış bitince bırakır; PLAN `14.4.10` 16. madde bunu böyle tanımlar. Yani ilk
kopya açıldıktan sonra ikinci bir kopya kapıdan geçip aynı veritabanını açabilir.
SQLite yazımları sıralar ve restore/onay transaction içi karşılaştırmayla korunur;
fakat Room'un invalidation tracker'ı başka süreçlerin yazımını görmez ve öbür
kopyanın ekranları bayat kalabilir. **İş 10 bu kapsamı değiştirmez:** tanılama
yazıcısı kendi süreç kilidini (`pnp-tanilama.lock`) alır (PLAN `14.7.1`). Tek
kopya politikası (kilidin uygulama ömrü boyunca tutulması) ayrı bir ürün
kararıdır ve ölçülmemiştir.

**İş 10 / Dilim 1'de uygulandı:** `DiagnosticLogSink` ilk satırda
`logs/pnp-tanilama.lock` kilidini alır ve süreç boyunca tutar; alamayan süreç hiç
yazmaz. İki gerçek süreçle ve SIGKILL ile ölçüldü (`DiagnosticLogProcessTest`).
Açılış kilidinin kapsamı değişmedi.

---

# 34. TASARIM İLKELERİ

## Veri bütünlüğü

- Tek kaynak veri; projection'larda duplicate üretme.
- Task ID üzerinden deduplicate et.
- ProgressEvent ve HistoryEvent çoğaltma.
- Soft delete / tombstone.
- Client-generated UUID.
- Kullanıcı işlemi ile onun kaydı **aynı transaction'da** yazılır; biri düşerse ikisi
  de düşer.

## Offline-first

- Uygulama internet olmadan bütün ana işlevlerini sürdürür.
- Telemetri varsayılan olarak yoktur.

## Import ve export güvenliği

- Kaynak dosyayı değiştirme.
- SHA-256 fingerprint, immutable snapshot, DRAFT katmanı, transaction, rollback.
- Export atomik yazar; hedef dosya hata hâlinde bayt bayt korunur.
- Gerçek DB'yi smoke test sırasında değiştirme.
- Dışarıdan gelen dosyaya güvenme: sınır → ayrıştırma → sürüm → checksum → yapı →
  domain → geçici DB, ve ancak sonra kullanıcı onayı. Doğrulama bitmeden kalıcı
  yazma yok.
- Yıkıcı bir işlem, kullanıcının geri dönüş yolunu kendi eliyle kurmadan başlamaz;
  güvenlik yedeği yazılamıyorsa işlem hiç başlamaz.
- Veritabanı dosyasını, WAL'ını veya SHM'sini dosya sistemi düzeyinde takas etme;
  değiştirilecek şey satırlardır ve tek transaction içinde değiştirilir.

## UI güvenliği

- Kaydedilmemiş taslağı kaybetme.
- Klavye erişimi ve görünür odak.
- Açık paneli gereksiz kapatma.
- Geliştirici exception'larını ve mutlak yolları kullanıcıya gösterme.

---

# 35. YENİ BİR OTURUM İÇİN BAŞLANGIÇ PROMPTU

```text
PNP projesinde çalışıyoruz.

Önce PNP_MASTER_CONTEXT.md'yi, ardından repodaki PLAN.md'yi oku.

KRİTİK:
- PLAN.md tek yetkili kaynaktır; çelişkide PLAN.md kazanır.
- PNP_MASTER_CONTEXT.md §36'daki tarihsel bilgileri güncel mimari sanma.
- Kapsam dışı kod yazma; bir dilimden diğerine kendiliğinden geçme.
- Beklenmeyen repo/HEAD/branch/working-tree durumunda değişiklik yapmadan dur.
- Gerçek kullanıcı DB'sini açma, kopyalama, migrate etme veya commit etme.
- Gerçek Excel dosyasını, kişisel yolu, e-postayı veya secret'ı commit etme.

Güncel mimari:
- Kotlin + Compose Multiplatform + Room KMP (androidx.room3) + bundled SQLite
- Tek modül "app", JVM hedefi "desktop", offline-first, Linux-first
- Room şema sürümü 8

Güncel domain:
- Game -> GameCell -> CellSegment -> Task
- Item / Component / parça sayacı modeli YOK
- Quantity Task'a aittir
- Tek öge çok renk = tek Task, 2+ renk
- Havuzlar Task kopyalamaz; salt okunur projection
- Havuz üyeliği tasks.pool_type üzerinden belirlenir

Havuzlar: 3D Baskı, Kartlar, Mukavva, Özel

İlerleme overwrite edilmez; ProgressEvent ile hareket olarak tutulur.
Görev yaşam döngüsü ayrı bir append-only history_events tablosuna yazılır.

Faz 1 ve Faz 2 tamamlandı. Faz 3 başladı:
- İş 1 (geçmiş) iki dilim hâlinde tamamlandı: olay kayıt katmanı + geçmiş ekranı.
- İş 2 (import rollback) üç dilimiyle TAMAMEN BİTTİ: Room v8 + import_batch_cells,
  geri alma motoru ve üç geçmiş olayı, ve motoru kullanan arayüz.
- İş 3 (sürümlü JSON yedek ve geri yükleme) dört dilimiyle TAMAMEN BİTTİ:
  belge + kanonik yazıcı + dataSha256, Ayarlar ekranı + atomik dosya yazma,
  güvenilmeyen dosyayı okuma/doğrulama + geçici Room v8 denemesi, ve güvenlik
  yedeği + canlı replace transaction + arayüz.
- İş 4 (otomatik snapshot + döngüsel saklama) dört dilimiyle TAMAMEN BİTTİ:
  adlar/sahiplik/rotation motoru, sürümlü settings.json + saklama sayısı,
  her içe aktarma onayı öncesi doğrulanmış snapshot + yarış koruması, ve
  migration öncesi eşleşmiş set + açılış kapısı. Kararlar PLAN 14.4.7-14.4.13,
  uygulanan hâli §25.2. Yeniden tartışma.
  İŞ 7 TAMAMLANDI. İŞ 5 TAMAMLANDI (CSV export matrisi §25; depolama okuma hatası
  tipli COULD_NOT_READ — export'ta SQLiteException'ı ham bırakma).
  İŞ 9 ÖLÇÜM DİLİMİ TAMAMLANDI (LargeLibraryPerformanceTest, §29): süre/bellek
  EŞİK DEĞİL kayıttır; eşik kararı kullanıcınındır. Açılış 21 sabit ifadedir.
  İŞ 8 TAMAMLANDI (AppKeyboardAndScalingTest, §17): tablo hücresi TEK odak hedefidir
  (focusable yanına clickable/combinedClickable EKLEME); geri alınamaz onaylarda
  başlangıç odağı Vazgeç'tir; oyun tablosunun üst kontrolleri kayar, tablo en az
  200 dp kalır. Yeni ekran eklenirse Screen.all üzerinden taramaya kendiliğinden girer.
  İŞ 9 TAMAMLANDI: kabul makineden bağımsızdır (doğru sonuç, 42/1.000+ aynı ifade
  yapısı, N+1 yok, arama/süzgeç 0 ifade, tekrarda aynı sonuç); süre/bellek EŞİK
  EKLEME, yalnız ortamla kayıt; aynı yöntemde 2 kat kötüleşmeyi raporla.
- İŞ 11 VE İŞ 12 TAMAMLANDI (§25.5). Kuralı bozma: sürüm yalnız app/build.gradle.kts
  `version`; AppInfo'ya veya pakete elle sürüm YAZMA. İş 12 arşivi paketler, ikinci
  derleme hattı KURMA. Paket denetimlerinde pencere araçlarını yalnız DesktopWindows.kt
  üzerinden kullan (SafeWindowCloserTest yüzey testi bunu çivili tutar). Gerçek
  sisteme paket kurma/sudo kullanma. Sıradaki: Faz 3 / İş 13 (temiz ortam, R6).
- İŞ 10 TAMAMLANDI (Dilim 4–8, §25.4). Kuralı bozma: zaman sırası bütünlük kuralı
  DEĞİLDİR (epoch düzeltme, sıralamada eşitliği id ile çöz); D1–D9 ve C/RB/U'nun tek
  tanımı domain/importhealth/ImportLifecycle.kt — ikinci kopya YAZMA; okuyucuyu
  SIKILAŞTIRMA, kapı RestoreController'da onaydan önce; açılışta yalnız quick_check
  (integrity/foreign_key_check EKLEME), Room'dan önceki okumalar
  ConsistentDatabaseClone.readWithoutTouching ile; hasarlı DB'yi onarma/taşıma/silme.
  Smoke: ./gradlew desktopWindowSmoke [-PsmokeScenario=damaged-database].
  Sıradaki: Faz 3 / İş 11 (paket türü, JRE, sürüm kaynağı — kullanıcı kararı).
- İŞ 10 STABİLİZASYON TURU BİTTİ (Dilim 3 ile 4 arası; üretim kodu değişmedi):
  tanılama testinde "close() dönünce N satır diskte" GİBİ HIZ VARSAYIMI YAZMA —
  işçiyle FaultyLogFileSystem'in olaylarıyla konuş (awaitLines, awaitLineWith,
  appendsBeforeGate/awaitHeldAtGate, awaitFirstLineOrRefusal); süreç testleri
  yalnız süreç/kilit/JSONL bütünlüğünü ölçer. Kapanış sınırını (500 ms) testi
  geçirmek için BÜYÜTME. Masaüstünde pencereye kısmi başlıkla (grep, -c <başlık>,
  :ACTIVE:) ASLA istek gönderme; tek yol SafeWindowCloser / ./gradlew
  desktopWindowSmoke. Yavaş makine için sistem güç ayarını DEĞİŞTİRME, kendi
  Gradle sürecine systemd-run CPUQuota ver. Kullanıcının masaüstünden alınan
  pencere başlıkları repoya YAZILMAZ.
- İŞ 10 / DİLİM 3 BİTTİ: Dilim 2'nin ÖLÇTÜĞÜ beş tipsiz kaçış tiplendi (taslak
  kaydı, oyun tablosu okuması, renk kataloğu okuması, havuz okuması, açılış
  dizinleri). Kuralı bozma: gözlenen bir okumada YALNIZ SQLiteException cevaplanır
  ve bunu TEK seam yapar (`answeringStorageRefusal`); `.catch {` veya geniş
  `catch (Throwable/Exception)` EKLEME; IllegalState/IllegalArgument/NullPointer/
  Error ve CancellationException AYNEN yükselir ve KAYDEDİLMEZ; reddedilen bir
  okumayı boş liste/boş tablo olarak ÇİZME (görünüm ve süzgeç değişimi dâhil);
  yeniden deneme `readAttempt` + LaunchedEffect anahtarıdır, otomatik yeniden
  deneme veya döngü EKLEME. Açılış dizini hatası StartupRefused
  (FOLDERS_NOT_CREATED) olur; hata mesajına MUTLAK YOL yazma. Yeni olay kodu YOK.
- İŞ 10 / DİLİM 2 BİTTİ: hata sınırları kayda bağlandı. Kuralı bozma: hatayı tipli
  sonuca çeviren katman kaydeder (store/gateway), controller AYNI hatayı ikinci kez
  YAZMAZ; kaydı her zaman transaction bittikten SONRA ver; recordSafely dışında
  record() çağırma; yeni olay kodu EKLEME (liste kapalı, PLAN 14.7.2). Sahiplik
  tablosu DiagnosticOwnershipTest'tedir; yeni bir sınır eklerken orayı da güncelle.
  Tipsiz kaçan hataların listesi ÖLÇÜLDÜ (§25.4 matrisi) — Dilim 3'ün kapsamı odur.
- İŞ 10 / DİLİM 1 BİTTİ: tanılama yazıcısı domain/diagnostics (DiagnosticRecord,
  diagnosticLineOf) + platform/diagnostics (DiagnosticLogFileSystem, DiagnosticLogSink,
  QueuedDiagnostics). Yeni olay kaydederken ikinci bir yazıcı, kuyruk, dosya adı
  veya rotation YAZMA; DiagnosticRecord'a String/Path parametresi EKLEME; Throwable'ı
  kayıtta TUTMA. Test ederken XDG_STATE_HOME geçici dizine; FaultyLogFileSystem ve
  LogHome kullan. Sıradaki: Dilim 2 (sınırları bağlamak).
- İŞ 10 TASARLANDI (PLAN 14.7, özet §25.4). Kurallar:
  log yalnız $XDG_STATE_HOME/pnp-tracker/logs/ (pnp-tanilama*.jsonl, 5 × 1 MiB);
  yeni bağımlılık EKLEME; kayıt API'sine String/Path/EntityId parametresi EKLEME;
  exception MESAJI, stack trace, dosya adı, yol, UUID, SQL YAZMA — yalnız sınıf ve
  kök neden sınıf adı; kayıt çağıranı bekletmez/düşürmez; instance kilidine
  güvenme (açılış sonrası bırakılır, R15), log kendi kilidini alır; hatayı tipli
  sonuca çevrildiği yerde TEK KEZ kaydet, başarıyı kaydetme (yalnız migration ve
  restore INFO). R12: zaman sırasını doğrulama kuralı yapma, zaman damgası
  düzeltme, rotation az önce yazılanı silmesin. R13: açılışta Room'dan önce salt
  okunur quick_check; hasarlı DB'yi onarma/taşıma/silme/takas YOK, uygulama içi
  kurtarma YOK. R14: UntrustedBackupReader'ı SIKILAŞTIRMA (snapshot ve migration
  setini de doğrular); önce adayları ölç (Dilim 5), sonra geri almada C2–C4
  (Dilim 6), sonra geri yükleme onayından önce bellekte L (Dilim 7). Smoke'ta
  XDG_STATE_HOME'u da geçici dizine ver.
- İş 7 / Dilim 1 BİTTİ (yalnız test): dört yazma yolu gerçek ikinci JVM'de
  transaction içinde ve commit sonrası SIGKILL ile kesildi; hepsi ya hep ya hiç.
  Ölçülen kalıcılık: journal_mode = wal, synchronous = 1 (NORMAL). synchronous'u
  DEĞİŞTİRME. Üretime crash hook / debug flag EKLEME; kesme noktası yalnız
  desktopTest'teki StoppingSqliteDriver'dadır ve DatabaseFactory'nin mevcut
  `driver` parametresinden girer.
- İş 7 / Dilim 2 BİTTİ: taslağı kaldırmanın TEK yolu ImportDao.removeDraftBatch
  (+ ImportDraftRemovalStore). discardDraftBatch YOK. Sonuçlar tiplidir:
  Removed / Refused(ALREADY_REMOVED | NOT_A_DRAFT | HELD_BY_RECORDS |
  COULD_NOT_SAVE); ret DAO'dan DEĞER olarak döner, yalnız SQLiteException
  COULD_NOT_SAVE'e çevrilir, IllegalStateException/IllegalArgumentException
  MASKELENMEZ. D6/D7 FK hatası beklenmeden açık SELECT ile bulunur. Postcondition
  15 tablonun sayımıdır; satır başına okuma/silme EKLEME (cascade tek DELETE'te).
  Kaldırma history YAZMAZ, snapshot ALMAZ, kaynak dosyayı OKUMAZ.
- İş 7 / Dilim 4 BİTTİ: Devam eden içe aktarmalar listesi UnfinishedImportsStore +
  UnfinishedImportsController + UnfinishedImportsSection'dır. Liste
  ImportDao.healthOfDraftBatches ile okunur (tek transaction, 3 okuma, tekil
  draftHealthOf ile AYNI saf fonksiyon) ve D1–D9 tablolarının invalidation
  flow'uyla tazelenir; satır başına sınıflandırma EKLEME. Devam et her basışta
  draftHealthOf'u yeniden okur; listeye güvenip inceleme ekranı AÇMA. Bozuk
  taslaklar ayrı bölümde, yalnız Kaldır. Kaldırma onayı odak Vazgeç, çalışırken
  Escape/dışarı tıklama etkisiz, tek transaction; sonuç motorun cevabıdır.
- İş 7 / Dilim 3 BİTTİ: D1–D9'un DOKUZU DA gerçek restore hattından canlı DB'ye
  ulaşır (ÖLÇÜLDÜ; PLAN listesi doğru, değişmedi). Sınıflandırmanın TEK yolu
  ImportDao.draftHealthOf → DraftHealth (Sound / Contradicting(nedenler) /
  NotFound / NotADraft); salt okunur, @Transaction, blok sayısından bağımsız 3
  okuma; D8 SQL'de üst küme + Kotlin'de UTF-16 kesin karar. Nedenler
  DraftContradiction'dır ve kullanıcıya GÖSTERİLMEZ. ImportConfirmationStore
  sınıflandırıcıyı snapshot'tan ÖNCE ve BEGIN IMMEDIATE içinde 15 tablo
  karşılaştırmasından SONRA çağırır; bozuksa RECORDS_CONTRADICT_EACH_OTHER,
  snapshot/rotation/yazma YOK. Yalnız Contradicting durdurur; diğer sonuçlar
  eski onay denetimlerine bırakılır. Programlama hataları MASKELENMEZ. Yeni bir
  bozukluk nedeni ancak gerçek restore hattıyla ölçülerek eklenir.
- İş 7'nin (beklenmeyen kapanış ve yarım kalmış içe aktarma) kararları VERİLMİŞTİR:
  PLAN 11.4.5, özet ve repo denetimi §25.3. Yeniden tartışma. İşaret dosyası YOK,
  -wal çökme kanıtı DEĞİL, güvence tek transaction + WAL kurtarması; DRAFT
  kalıcıdır ve HİÇBİR DRAFT otomatik onaylanmaz/silinmez/değiştirilmez; kaynak
  dosya yeniden okunmaz; kaldırma tek transaction, tipli ve tekrar çağrı
  güvenli; "bozuk" YALNIZ D1–D9 predicate'leridir; denetim salt okunur ve
  açılışı engellemez; bozuk taslak onaylanamaz, yalnız açık onayla kaldırılır;
  ham .db restore YOK; integrity_check → R13; yedek okuyucusu İş 7'de
  sıkılaştırılmaz → R14; R12 → İş 10. Dört dilim, Room şeması değişmez.
- İş 4'ün verilmiş kararları, kısaca: eşik YOK (her içe aktarma onayı
  yedeklenir, XLSX/CSV ayrımı yok); snapshot confirmDraftBatch'ten HEMEN ÖNCE;
  yarış koruması restore'un modelidir (transaction içi yeniden doğrulama +
  fail closed), global mutation barrier DEĞİL; migration için İKİ eşleşmiş
  artefakt (ham .db + yürütülmüş .json) ve ikisi de doğrulanmadan set başarılı
  sayılmaz; ÜÇ AYRI kota (import / pnp-oncesi / migration seti), türler arası
  ortak kota YOK; automaticBackupCount 1..50, varsayılan 7, 0 GEÇERSİZ;
  settings.json DB'nin DIŞINDA ve yedek kapsamına GİRMEZ; sayı azalınca dosya
  HEMEN silinmez; rotation hatası ana işlemi ENGELLEMEZ; startup migration hata
  penceresi İŞ 4 kapsamındadır.
- İş 4 ŞEMA DEĞİŞİKLİĞİ GEREKTİRMEZ; Room sürümü 8 kalır ve migration zinciri
  Migration1To2 … Migration7To8 olarak kalır.
- O GEÇİCİ KURAL KALKTI: açılış kapısı yazıldı, korumasız migration artık kod
  tarafından imkânsızdır (§33 R10). Gerçek DB hâlâ şema v3'tedir ve bu turda
  AÇILMADI; ilk gerçek migration'ı tetiklemek kullanıcının kararıdır.
- Uygulamanın veritabanını açan tek üretim yolu StartupGate'tir. Main'den
  DatabaseFactory().open(...) ÇAĞIRMA; DatabaseFactory'ye housekeeping veya
  snapshot alıcı EKLEME (kapı, çalışma kopyasını o fabrikadan geçirir).
- Ham migration klonu için mekanizma ÖLÇÜLMÜŞTÜR: salt okunur bağlantı +
  VACUUM INTO (§33 R11). Okuma-yazma açmak kaynağı değiştirir; DB + -wal + -shm
  sırayla kopyalamak YASAKTIR.
- İş 2'nin ürün kararları VERİLMİŞTİR ve PLAN 11.4.4'tedir; yeniden tartışma.
  Kısmi rollback yoktur, tek çakışma bütün işlemi engeller, segment kimliğine
  provenance bağlanmaz, anlık görüntüsü olmayan eski batch geri alınamaz.
- İş 3'ün ürün ve mimari kararları da VERİLMİŞTİR: PLAN 14.4 / 12.16 / 16., özet
  §25.1. Yeniden tartışma. Düz UTF-8 JSON (.json, sıkıştırma yok), 64 MiB sınırı,
  bilinmeyen alan REDDEDİLİR, dataSha256 zorunlu, 15 tablonun tamamı, merge DEĞİL
  replace, DB/WAL/SHM dosya takası YOK, canlı DB'de tek transaction, restore
  öncesi güvenlik yedeği zorunlu, restore history event YAZMAZ, dört dilim.
- kotlinx-serialization-json 1.11.0 ve serialization eklentisi Dilim 1'de
  EKLENDİ (yalnız commonMain); izin başka bağımlılığa genişletilmez.
- Yedek belgesi JSON compact yazılır (pretty-print YOK) çünkü gömülü data ile
  hash'lenen data bayt bayt aynı olmak zorundadır.
- Ayarlar ekranı AÇIKTIR ve `Yedek oluştur` ile `Yedekten geri yükle` eylemlerinin
  ikisini de içerir; ikisi de çalışır.
- Yedek boru hattı BÜTÜNÜYLE hazırdır: DatabaseBackupExporter + BackupStore
  (yazma), UntrustedBackupReader + TemporaryBackupProbe + PathBackupInput
  (okuma), DesktopSafetyBackupWriter + LiveBackupRestorer (geri yükleme).
  Doğrulamayı yeniden yazma, ikinci bir JSON parser, ikinci bir checksum
  implementasyonu veya ikinci bir tablo yazıcısı EKLEME; geçici DB denemesi ile
  canlı restore `replaceEverythingWith`'i PAYLAŞIR ve paylaşmaya devam etmelidir.
- Canlı DB'yi boşaltabilen tek üretim API'si `LiveBackupRestorer`'dır ve yalnız
  `ValidatedBackup` + `SafetySnapshot` kabul eder. AppDatabase'e restore DAO'su
  EKLEME.
- İçe aktarma onayı ARTIK otomatik snapshot'ın arkasındadır: `ImportConfirmation`
  bir `AutomaticSnapshotTaker` ve bir `AutomaticBackupHousekeeping` alır, ve
  `AutomaticSnapshot` yalnız dosya yazılıp GERÇEK okuyucuyla doğrulandıktan sonra
  üretilebilir (internal yapıcı). Bu kapıyı gevşetme, eşik ekleme, XLSX/CSV ayrımı
  yapma, ve transaction içi yeniden doğrulamayı kaldırma (§25.2).
- Otomatik yedeklerin adını sahiplenen tek yer `ClaimedNameWriter`'dır; üçüncü bir
  ad sahiplenme kalıbı yazma.
- BackupRejection'a Throwable, yol, UUID, SQL veya kullanıcı metni EKLEME.
- TemporaryBackupProbe dışarıdan yol veya AppDatabase KABUL ETMEZ ve canlı DB'de
  replace SUNMAZ; bu sınır korunur — canlı yazım ayrı bir sınıftır
  (LiveBackupRestorer) ve ikisi yalnız tablo yazıcısını paylaşır.
- AtomicFileWriter platform.files altındadır ve CSV ile yedek onu PAYLAŞIR;
  ikinci bir atomik yazıcı yazma.
- Yabancı anahtarlar üretim bağlantısında ZORLANIR (ölçüldü, §33 R9).
- Batch durumu ekranda ham enum olarak GÖSTERİLMEZ; ui/PoolNames.kt içindeki
  importStatusNameOf tek kaynaktır.
- PLAN'a satır numarasıyla atıf yapma; bölüm numarası kullan (PLAN 12.15 gibi).

Şimdi:
1. Repo durumunu doğrula (branch/HEAD/temiz ağaç/Room sürümü/PLAN hash/schema hash).
2. PLAN.md'nin ilgili bölümlerini oku.
3. Yalnız o dilimin kapsamını uygula.
4. Testleri çalıştır: ./gradlew clean check --rerun-tasks
   (yalnız belge turuysa test çalıştırma; tutarlılık ve hash taraması yap.)
5. Geçici XDG ile UI smoke + DB invariant + git status doğrula.
6. Gerekiyorsa tek atomik commit oluştur.
7. Sonraki işe geçme.
```

---

# 36. TARİHSEL / ARTIK GEÇERLİ DEĞİL

> Bu bölümdeki her şey **geçmişte doğruydu ve bugün yanlıştır.** Rehber olarak
> kullanılmamalıdır; buraya yalnız eski notlarda veya eski commit mesajlarında
> görüldüğünde tanınabilsin diye konmuştur.

## AP adımlandırması — kullanılmıyor

Geliştirme bir dönem `AP-1 … AP-10` diye adlandırılan adımlarla yürütüldü. **PLAN.md
AP'lerden hiç söz etmez.** Güncel çalışma birimi faz ve numaralı iştir (§3, §32).

Eski zincir ve commit'leri:

```text
AP-7   e2ad96c  feat: add desktop window shell with sidebar navigation and turkish string catalog
AP-8   0152a1a  feat: read xlsx sheets into an immutable snapshot with anonymised test fixture
AP-8f  eb5e36b  fix: avoid masking unexpected xlsx reader failures
AP-9   47582d2  feat: detect completion green-cell and column hints without applying them
AP-10  63b4845  feat: import xlsx file into draft batch with fingerprint and raw blocks
```

`47582d2` (AP-9 sonu) reponun **10. commit'idir**; o noktadan bu yana 54 commit daha
atılmıştır. Bu dosyanın önceki sürümü o anın fotoğrafıydı.

## Room şema v3 — geçersiz

Eski metin "Room schema v3'e kadar ilerlenmiş" ve "korunması gerekenler: 1.json,
2.json, 3.json" diyordu. **Güncel sürüm 8'dir**; korunacaklar `1.json … 8.json`'dır.

Eski "gerçek XDG DB kontrolü" listesi `user_version = 3` ve "Game/Item/Task/import
tabloları boş" bekliyordu. Bu beklentiler artık geçersizdir.

## Canlı `Item` modeli — geçersiz

Eski metin `Game → Item → Task` zincirini ve "Item oyun içindeki fiziksel bir öğedir"
tanımını canlı model olarak anlatıyordu. **`items` tablosu şema v4'te düşürülmüştür**
(commit `16a2848`); kullanıcıya gösterilen öge kavramı kaldırılmıştır (PLAN 5.13).
Güncel zincir `Game → GameCell → CellSegment → Task`'tır.

Faz 1'in "manuel oyun ve öge kurulumu" işi o zamanki modeli tarif eder; kazanımları
geçerli altyapıdır, öge yolu Faz 2'de kontrollü olarak sökülmüştür.

## "Sonraki büyük aşama DRAFT import batch" — geçersiz

Eski özet, sıradaki işi DRAFT import batch yazımı olarak gösteriyordu. O iş
`63b4845` ile yapılmış, üstüne inceleme ekranı, onay transaction'ı, tablo-öncelikli
oyun çalışma alanı, dört havuz, aşamalar, renk kataloğu, arama/filtre, CSV import,
CSV export ve geçmiş olay katmanı gelmiştir.

## Diğer superseded kararlar

```text
PWA yaklaşımı              → Compose Multiplatform seçildi
Component / parça modeli   → yok
Renk başına quantity       → quantity Task'a aittir
Havuzda kopya Task         → salt okunur projection
Tracking mode → havuz      → havuz tasks.pool_type ile belirlenir
Completion hint → completed→ ipucu asla otomatik uygulanmaz, PENDING kalır
Renk arşivleme             → yok; renk fiziksel silinir
Görev/oyun arşivleme       → yok
ALTERNATIVE renk ilişkisi  → sökülmüştür
```

---

# 37. KALİTE KONTROL CHECKLIST

Her dilim sonunda:

```text
[ ] PLAN.md değişmedi (hash doğrulandı)
[ ] Beklenen branch ve HEAD
[ ] Working tree başlangıçta temizdi
[ ] ./gradlew clean check --rerun-tasks geçti
[ ] Test sayısı ve 0 hata raporlandı
[ ] git diff --check temiz
[ ] Geçici XDG ile ./gradlew run smoke yapıldı
[ ] UI regresyonu yok; gezinme ve tema çalışıyor
[ ] Dar pencerede taşma yok
[ ] Normal kapanış; arkada process kalmadı (./gradlew --stop)
[ ] Pencere yalnız SafeWindowCloser ile kapatıldı; kullanıcının pencere listesi aynı
[ ] Gerçek DB hash + boyut + mtime önce/sonra aynı
[ ] Gerçek $XDG_STATE_HOME/pnp-tracker (logs) önce/sonra aynı (İş 10 sonrası)
[ ] Üretilen kayıt satırlarında kullanıcı metni, dosya adı, yol, UUID, SQL, mesaj yok
[ ] Room şema sürümü beklenen değer
[ ] Eski schema JSON'ları değişmedi; yeni JSON commit edildi
[ ] Seed renkler korunuyor
[ ] Fixture hash kontrol edildi
[ ] Sorgu sayımı invariant'ları korundu (§29)
[ ] Kodda satır numaralı PLAN atıfı yok (§33 R8 taraması)
[ ] Gerçek Excel / kişisel yol / e-posta / secret / build çıktısı commit edilmedi
[ ] Yeni bağımlılık yalnızca PLAN/dilim izin veriyorsa eklendi
[ ] Geçici dizin ve dosya kalıntısı temizlendi
[ ] git status --short boş
[ ] Tek atomik commit
[ ] Sonraki işe geçilmedi
```

---

# 38. SON DURUM ÖZETİ

PNP artık fikir aşamasında bir uygulama değildir; **günlük üretim takibinde
kullanılabilir durumdadır.**

Bugün çalışan hâliyle:

- Kullanıcı Excel olmadan oyun satırı, hücre metni ve görev oluşturabilir.
- Oyun tablosu üç global görünümde çalışır; görevler hücrelerin içinde inline'dır.
- Üç oluşturma modu (tek renk, çoklu görev, tek öge çok renk) beklenen kayıtları üretir.
- Dört havuz salt okunur projection olarak çalışır ve kopya görev üretmez.
- Renk kataloğu isimli renklerle çalışır; silme ve temel renkleri geri yükleme çalışır.
- 3D ana baskı, eksik/hatalı bildirim ve eksik giderme çalışır.
- Kart ve mukavva üretimi aşamalarla takip edilir.
- Oyun toplu tamamlama ve eksik parçada yeniden açılma çalışır.
- Arama ve dört filtre ailesi sıfır ek sorguyla çalışır.
- XLSX ve CSV içe aktarma aynı boru hattını kullanır.
- Yapılandırılmış görevler deterministik, atomik ve formül-güvenli CSV'ye aktarılır.
- Görev yaşam döngüsü append-only olarak kayda geçer.
- Geçmiş ekranı bu kaydı okunur hâlde, oyun ve tarih süzgeçleriyle, iki sorguda
  gösterir; silinmiş ve metne dönüştürülmüş kayıtların satırları kaybolmaz.
- İçe aktarma onayı, yazdığı her hücrenin önceki metnini kalıcı olarak saklar ve
  dokunduğu her oyun için geçmişe bir satır yazar.
- Onaylanmış bir içe aktarma, hiçbir görevine ve hiçbir hücresine dokunulmamışsa
  tek transaction'da geri alınabilir: görevler tombstone olur, hücreler harfi
  harfine eski hâline döner, kullanıcının kendi parçaları kimlikleriyle korunur.
  Tek bir çakışma bütün işlemi engeller ve hiçbir satır değişmez.
- Kullanıcı bunu **kendi başlatır**: İçe Aktarma ekranındaki `Onaylanmış içe
  aktarmalar` listesinden geri alma istenir, önizleme okunur, onay verilir;
  engelleniyorsa neyin engellediği adlarıyla gösterilir ve onay düğmesi hiç
  görünmez. Sonuç Geçmiş ekranında oyun ve görev satırları olarak durur.
- Bir içe aktarmayı onaylamak, önce bütün veritabanının doğrulanmış bir
  yedeğini `backups/` altına yazar; yedek alınamazsa onay hiç başlamaz ve
  hiçbir satır değişmez.
- Onaylanmamış bir içe aktarmaya `Devam eden içe aktarmalar` listesinden kaynak
  dosya olmadan devam edilebilir ya da açık onayla kaldırılabilir; kayıtları
  uyuşmayan taslaklar ayrı bölümde yalnız kaldırılabilir.
- Kayıtları birbiriyle çelişen bir içe aktarma taslağı — bugün yalnız elle
  değiştirilmiş bir yedeğin geri yüklenmesiyle oluşabilir — onaylanamaz: onay
  bunu yedek almadan önce ve transaction içinde yeniden tanır, hiçbir şey
  yazmaz ve kullanıcıya tek bir Türkçe cümle söyler.
- Kullanıcı bütün verisinin sürümlü, deterministik ve checksum'lı bir JSON
  yedeğini `Ayarlar` ekranından istediği yere atomik olarak kaydedebilir.
- Ve o yedeği **geri yükleyebilir**. Dosya hiçbir şeyine güvenilmeden okunur;
  yalnız geçemeyen bir dosya için yıkıcı onay hiç gösterilmez; onaydan sonra
  mevcut verinin zorunlu güvenlik yedeği `backups/` altına yazılır; canlı
  veritabanı tek transaction'da yedeğin yerine geçer; ve her başarısızlık
  yolunda veri işlemden önceki hâlinde kalır. Geri yüklenmiş bir `CONFIRMED`
  içe aktarma hâlâ geri alınabilir.

Kalan iş ağırlıklı olarak **tanılama, paketleme ve yayına hazırlıktır**:
İş 10'un sekiz kod dilimi, Linux paketi, belgeler, lisans ve CI. Dayanıklılık
tarafı — sürümlü yedek, geri yükleme, otomatik snapshot ve yarım kalmış içe
aktarma kurtarması — bitmiştir; büyük veri performansı (İş 9) makineden bağımsız
ölçütlerle kapanmıştır.

**İş 10'un sözleşmesi yazılmıştır (PLAN `14.7`, §25.4).** Uygulama hataları
kullanıcı verisi taşımayan, sınırlı ve döngüsel bir JSON Lines kaydına yazacak;
saati geri gitmiş bir makinede yedek ve içe aktarma çalışmaya devam edecek;
hasarlı bir veritabanı açılmayacak ve dokunulmadan korunacak; elle hazırlanmış
bir yedekteki çelişkili içe aktarma kayıtları geri yükleme onayından önce
reddedilecek ve geri alma yalnız kendi ürettiği görevleri kaldıracak. Bunların
ilki — sınırlı, kullanıcı verisi taşımayan tanılama kaydı yazıcısı — Dilim 1'de
yazıldı, ve Dilim 2'de uygulamanın bütün tipli hata sınırlarına bağlandı: bir hata
artık tipli sonuca çevrildiği yerde bir kez kaydediliyor, kullanıcı hiçbir fark
görmüyor, ve bir işi başarıyla bitiren bir gün hiç satır yazmıyor. Dilim 3'te de
o kaydın ölçtüğü beş boşluk kapandı: bir taslak kaydedilemezse ekran artık
"kaydediliyor"da asılı kalmıyor, önizlemeyi koruyup tekrar denemeyi öneriyor;
oyun tablosu, renk kataloğu ve havuz okunamadığında kullanıcı boş bir liste değil
"okunamadı" ve bir `Yeniden dene` düğmesi görüyor; ve uygulama kendi klasörlerini
oluşturamazsa veritabanına hiç dokunmadan, hiçbir yol göstermeden Türkçe bir
açılış ekranı açıyor. Aynı dilimde havuzun her `Throwable`'ı yutan yakalaması
kaldırıldı: bir programlama hatası artık depolama sorunu gibi görünmüyor.
Ardından gelen stabilizasyon turu doğrulamanın kendisini sağlamlaştırdı: tanılama
testleri artık makinenin hızını değil sözleşmeyi ölçüyor, tam koşu prizde olmayı
gerektirmiyor, ve masaüstü smoke'u yalnız kendi başlattığı sürecin, tam başlıklı
ve kapatmadan hemen önce yeniden doğrulanmış penceresini kapatıyor.
İş 10'un son beş dilimi veri bütünlüğünü kapattı: saat geriye gidince yedekler
ve içe aktarma kullanılabilir kalıyor; bir içe aktarmanın birbirini tutmayan
kayıtları ne geri almada başka bir görevi kaldırabiliyor ne de bir yedekle
canlı veritabanına girebiliyor; ve hasarlı bir veritabanı, Room onu açmadan ve
hiçbir dosyaya dokunulmadan, Türkçe bir açılış ekranıyla reddediliyor.
**İş 10 TAMAMLANDI.** Ardından İş 11 ve İş 12 uygulamayı Java'sız, belirlenimci
bir Linux arşivine ve Garuda/Arch paketine dönüştürdü; ikisi de gerçek
başlatıcılarıyla depo dışından çalıştırılıp güvenle kapatıldı.
**Sıradaki bağlayıcı iş Faz 3 / İş 13'tür.**

Bunların ilki — **sürümlü JSON yedek ve geri yükleme** — dört atomik dilimde
**tamamlanmıştır**. Biçim, kapsam, doğrulama hattı, restore mimarisi (A′),
güvenlik yedeği ve dört dilim PLAN `14.4` ile §25.1'de yazılıdır.

**Otomatik snapshot** (PLAN Faz 3 / iş 4) da **tamamlanmıştır**; bağlayıcı metni
PLAN `14.4.7`–`14.4.13`, uygulanan hâli §25.2'dedir.

Alınan kararların özü: eşik yoktur — **her** içe aktarma onayı, `XLSX`/`CSV`
ayrımı gözetmeden ve domain yazımından hemen önce yedeklenir; snapshot ile onay
arasındaki yarış, geri yüklemenin kanıtlanmış modeliyle (transaction içi yeniden
doğrulama ve fail closed) korunur; migration için **iki eşleşmiş artefakt**
üretilir — migration kodunu hiç çalıştırmamış ham bir kopya ve yürütülmüş bir
kopyadan üretilen, uygulama içinden geri yüklenebilir kanonik JSON — ve set ancak
ikisi de doğrulandığında başarılı sayılır; saklama **tür başına ayrı kotalarla**
yapılır, böylece bir içe aktarma yoğunluğu kullanıcının geri dönüş yolunu
tahliye edemez; ve sayı `Ayarlar` ekranından `1..50` aralığında değiştirilir,
varsayılanı `7`'dir, `0` geçersizdir.

İş dört atomik dilimde uygulanmıştır. **Dördü de bitmiştir.**

Dilim 1 otomatik yedeklerin adlarını, sahipliklerinin iki bağımsız kanıtla
doğrulanmasını ve üç bağımsız kota için döngüsel saklama motorunu getirdi. Motor
yalnız adının kalıbıyla **ve** ilk baytlarıyla kendisinin olduğunu
gösterebildiği dosyaları siler; manuel yedekler, bilinmeyen dosyalar,
bağlantılar ve eksik eşli migration setleri erişiminin dışındadır.

Dilim 2 kullanıcıya o sayıyı verdi. `Ayarlar` ekranında `1..50` aralığında,
varsayılanı `7` olan gerçek ve kalıcı bir ayar var; `0` geçersizdir ve otomatik
koruma kapatılamaz. Ayar veritabanının **dışında**, sürümlü ve atomik yazılan bir
`settings.json` dosyasında durur — bir geri yükleme onu değiştirmez. Dosya yoksa
varsayılan kullanılır ve **dosya oluşturulmaz**; bozuksa varsayılan kullanılır,
sebebi ekranda Türkçe olarak söylenir ve **dosyanın üzerine yazılmaz**. Dosya
yalnız kullanıcı Kaydet'e bastığında oluşur. Sayıyı küçültmek hiçbir şeyi o anda
silmez: yeni değer kaydedilir ve fazlası bir sonraki otomatik yedeğin ardından
temizlenir. Bugün o "sonraki otomatik yedek" restore öncesi güvenlik yedeğidir.

Dilim 3 sözü tuttu: artık **her** içe aktarma onayı — XLSX olsun CSV olsun, bir
görevlik olsun kırk görevlik olsun — domain verisine ilk yazımdan önce kanonik
bir JSON yedeğin arkasında çalışıyor. Yedek yazılmakla kalmıyor, **gerçek
okuyucuyla geri okunup** satır satır ve checksum'ıyla karşılaştırılıyor; ancak
o zaman ortada bir snapshot oluyor ve onay ancak bir snapshot varsa
başlayabiliyor — bu, hatırlanacak bir kural değil, tiplerin bir özelliği.
Snapshot ile transaction arasındaki yarış, geri yüklemenin kanıtlanmış modeliyle
kapatılıyor: `BEGIN IMMEDIATE`, 15 tablonun yeniden okunması ve tam
karşılaştırma; arada bir şey değiştiyse tek bir satır yazılmadan reddediliyor.
Yedek alınamaz, yazılamaz veya doğrulanamazsa onay hiç başlamıyor ve kullanıcı
dört ayrı Türkçe cümleden birini görüyor. Onay penceresi, işlemden önce yedek
alınacağını tek bir cümleyle söylüyor.

Dilim 4 sonuncu boşluğu kapattı ve en sessiz olanını: bir güncellemeden sonraki
ilk açılışı. Uygulama artık veritabanını yalnız **açılış kapısından** açıyor.
Kapı önce bir instance kilidi alıyor — işletim sisteminin kilidi, çünkü onu
çökmüş bir kopyadan geri almak için kimsenin bir şey yapması gerekmiyor; sonra
şema sürümünü **Room'u hiç açmadan** okuyor; ve eski bir sürüm görürse gerçek
migration'ın önüne **iki eşleşmiş artefakt** koyuyor: migration kodunun hiç
çalışmadığı ham bir SQLite klonu ve bu klonun ayrı bir kopyasının gerçek
zincirle taşınmasından üretilen, `Ayarlar` ekranından normal biçimde geri
yüklenebilen bir JSON yedeği. İkisi de yazılıp **kanıtlanmadan** gerçek
veritabanı açılamıyor; bu bir kural değil, tipin bir özelliği. Herhangi bir adım
düşerse veritabanı bulunduğu sürümde kalıyor ve kullanıcı sekiz sebepten birini
söyleyen Türkçe bir açılış ekranı görüyor. Gerçek migration sonradan düşerse
alınmış set diskte kalıyor ve ekran onu işaret ediyor.

Ham klonun mekanizması **ölçüldü, varsayılmadı** (§33 R11): salt okunur bir
bağlantı üzerinde `VACUUM INTO`. Ölçüm, checkpoint edilmemiş bir WAL taşıyan —
yani bir çökmeden sonra kalan — veritabanının eksiksiz klonlandığını, sürüm
numarasının korunduğunu ve kaynağın bayt bayt değişmediğini gösterdi; ve aynı
ölçüm, sıradan bir okuma-yazma açılışının kaynağı **değiştirdiğini** gösterdi,
ki salt okunurluğun neden zorunlu olduğunun cevabı budur.

**Böylece Faz 3 / İş 4 tamamlanmıştır** ve kullanıcının verisi artık üç ayrı
noktada, kendisi istemeden ve kendisi hatırlamak zorunda kalmadan korunuyor:
geri yüklemeden önce, her içe aktarma onayından önce, ve her migration'dan önce.

İş 4'ten sonraki bağlayıcı iş **İş 7** idi (bugün tamamlandı): beklenmeyen kapanış ve bozuk import
kurtarma. Sözleşmesi yazılmıştır (PLAN `11.4.5`, §25.3). **Dilim 1 bitti:**
taslak kaydı, taslak düzenleme, onay ve geri alma gerçek bir ikinci süreçte
transaction'ın ortasında öldürüldü ve her seferinde veritabanı işlemden önceki
hâline bayt bayt döndü; commit'ten sonra öldürülünce commit edilen her şey
yerindeydi. Kalıcılık ayarı ölçüldü (`wal`, `synchronous = NORMAL`). **Dilim 2
bitti:** onaylanmamış bir içe aktarma artık tek transaction'da, yalnız kendi
satırlarıyla ve tipli bir sonuçla kaldırılabiliyor; ikinci istek sessiz başarı
ya da exception değil "zaten kaldırılmış" cevabı alıyor, gerçek bir görev veya
oyun başvuruyorsa hiçbir şey silinmiyor, ve yarıda kalan her deneme veritabanını
olduğu gibi bırakıyor. Kullanıcının bu eyleme ulaşacağı düğme henüz yok (Dilim
4). **Dilim 3 bitti:** kayıtları birbiriyle çelişen bir taslağın canlı veritabanına
nereden gelebileceği tahmin edilmedi, ölçüldü — dokuz çelişkinin dokuzu da
checksum'ı yeniden hesaplanmış bir yedeğin gerçek geri yüklemesiyle içeri
girebiliyor. Uygulama artık bunları salt okunur olarak tanıyor ve böyle bir
taslağın onayını, otomatik yedeği bile almadan, tek bir satır yazmadan
reddediyor; kullanıcı bu durumda neyin değişmediğini söyleyen tek bir Türkçe
cümle görüyor. **Dilim 4 bitti ve İş 7 tamamlandı:** İçe Aktarma ekranındaki
listede kullanıcı onaylanmamış bir içe aktarmaya — kaynak dosya silinmiş ya da
değişmiş olsa bile — kaldığı yerden devam edebiliyor ya da açık bir onayla
kaldırabiliyor; kayıtları uyuşmayan taslaklar ayrı bir bölümde, Türkçe bir
uyarıyla ve yalnız kaldırma seçeneğiyle duruyor. Liste eskimiş olsa bile hiçbir
taslak yeniden denetlenmeden inceleme ekranına girmiyor.

İş 7'nin belge turu bir şeyi netleştirdi: bu işin yükü hayalî bozukluk
durumlarını avlamak **değildir**. Uygulamanın kendi yolları yarım bir içe
aktarma bırakamaz — her yazma tek transaction'dır ve SQLite'ın kurtarması
commit edilmiş hâli geri getirir. İş, bu güvenceyi kalıcı testlerle sabitlemek,
kullanıcıya bugün var olmayan "taslağı kaldır" eylemini güvenli biçimde vermek,
ve tek gerçek giriş kapısı olan geri yüklemeden gelebilecek dokuz kesin
tutarsızlığı ölçüp onaydan uzak tutmaktır.

> **O geçici kural kalktı.** Açılış kapısı yazıldı; korumasız bir migration artık
> kod tarafından imkânsızdır. Gerçek kullanıcı veritabanı hâlâ şema v3'tedir ve
> **bu geliştirme turunda açılmadı, kopyalanmadı ve migrate edilmedi** — yalnız
> açılmadan ölçülüp değişmediği doğrulandı. İlk gerçek migration'ı tetiklemek
> kullanıcının kendi kararıdır; tetiklendiğinde kapı, eşleşmiş seti onun önüne
> koyacaktır (§0, §33 R10).

En önemli kural:

> **Eski sohbetleri tekrar okumaya çalışmak yerine önce PLAN.md + bu dosyayı oku,
> sonra mevcut repo durumunu doğrula.**
