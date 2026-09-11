# PNP — MASTER CONTEXT

> Bu dosya, yeni bir AI/Claude Code oturumunun eski sohbetleri baştan okumadan doğru
> çalışma bağlamını kurabilmesi içindir: **mevcut mimari, veri modeli, invariant'lar,
> UX kuralları ve geliştirmenin bulunduğu nokta.**
>
> **PLAN.md tek yetkili kaynaktır.** Bu dosya PLAN.md'nin yerine geçmez, onu özetler ve
> repo durumuyla ilişkilendirir. Çelişki hâlinde PLAN.md kazanır.
>
> **Son güncelleme:** Faz 3 / İş 4'ün **birinci dilimi** (otomatik yedek adları,
> sahiplik kanıtı ve tür başına döngüsel saklama motoru) tamamlandıktan sonra.
> İş 2 ve İş 3 bütünüyle bitmiştir; İş 4'ün dört diliminden **biri**
> yapılmıştır. İş 3'ün bağlayıcı metni PLAN `14.4.1`–`14.4.6`, `12.16` ve
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
HEAD (bu commit öncesi): a4c6761e0ddc9a36ef8b05ca92c267e903ffcfc6
önceki commit         : docs: define automatic snapshot and retention semantics
working tree          : temiz
Room şema sürümü      : 8   (bu commit'te DEĞİŞMEDİ)
şema dosyaları        : 1.json … 8.json  hepsi bayt bayt aynı
test durumu           : 3248 test / 0 failure / 0 error / 0 skipped  (211 sınıf)
                        [önceki commit: 3203 test / 206 sınıf]
üretim kodu           : 284 dosya
test kodu             : 224 dosya
PLAN.md               : bu commit'te DEĞİŞMEDİ
```

**Bu commit Faz 3 / İş 4'ün birinci dilimidir.** Otomatik yedeklerin adları,
sahipliklerinin kanıtlanması ve üç bağımsız kota için döngüsel saklama motoru
yazıldı. **Hiçbir üretim tetikleyicisi bağlanmadı:** ne içe aktarma onayı, ne
migration, ne de `Ayarlar` ekranı bu koda dokunuyor — `Main.kt` değişmedi ve
`AutomaticBackupRotation` üretim yollarından çağrılmıyor. `settings.json` hâlâ
yalnız bir yoldur; okunmuyor ve yazılmıyor (Dilim 2).

Kullanıcının gördüğü akış ve sırası:

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

`PLAN.md` bu turda **değiştirilmemiştir.**

## Doğrulama hash'leri

```text
PLAN.md  db891ba8362bb5ee837535aa042b8414ac2062d97a8fa25bff744884b09a3455
         (bu commit PLAN'ı DEĞİŞTİRMEZ; değer bir önceki commit'ten aynen gelir)

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
`assertRealApplicationDatabaseUntouched` yardımcı fonksiyonuyla **90 test sınıfında**
uygulanmaktadır. Sayı tek bir yerde tutulur; §29 aynı değeri anar ve tarama
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

> **Geçici kural — Faz 3 / İş 4 / Dilim 4 tamamlanana kadar geçerlidir:**
> gerçek uygulama **normal XDG diziniyle açılmamalıdır.** Bugün açılırsa
> migration **snapshot'sız** çalışır ve PLAN `14.4.10`'un açılış kapısı henüz
> yoktur. Geliştirme ve manuel doğrulama turları geçici XDG dizinleriyle
> yapılmaya devam eder. Dilim 4 bittiğinde bu kural kalkar.

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
ekleyecektir — saklanacak otomatik yedek sayısı (`1..50`, varsayılan `7`); başka
ayar alanı eklenmez (§25.2).

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
kullanıcı onayı → tek transaction
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

# 25.2 OTOMATİK SNAPSHOT VE DÖNGÜSEL SAKLAMA  *(Faz 3 / İş 4 — DİLİM 1 BİTTİ)*

Bağlayıcı metin PLAN `14.4.7`–`14.4.13`'tedir. Aşağısı alınan kararların özeti,
gerekçeleri ve **Dilim 1'de uygulanan hâlidir**. Dilim 2, 3 ve 4 yapılmamıştır.

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

Çakışma, Dilim 4'ün atomik sahiplenme kalıbıyla çözülür (`Files.createFile`,
sonra `-2`, `-3`). **Bir migration setinin iki eşi AYNI soneki taşır**
(`…-2.db` + `…-2.json`); ikisi adlarından eşleştirilebilir olmalıdır.

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

2  Sürümlü settings.json + saklama sayısı Ayarlar UI'sı ........... YAPILMADI
   ayar gerçekten kaydedilir ve gerçekten uygulanır
   commit: feat(settings): let the number of automatic backups be chosen

3  Her XLSX/CSV confirmation öncesi JSON snapshot + yarış koruması  YAPILMADI
   commit: feat(import): save the data before an import changes it

4  Migration öncesi ham DB + yürütülmüş JSON seti + açılış kapısı . YAPILMADI
   commit: feat(backup): save the database before a migration changes it
```

Dilimler **bu sırayla** uygulanır: Dilim 3 ve 4, Dilim 1'in yazıcısını ve
Dilim 2'nin sayısını kullanır. **Dilim 4 tamamlanana kadar gerçek uygulama normal
kullanıcı XDG'siyle açılmaz** (§0).

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

`settings.json` bugün **yalnız bir yoldur**: onu okuyan veya yazan üretim kodu
yoktur ve üç test (`AppDirectoryInitializerTest`, `BackupSmokeTest`,
`BackupRestoreSmokeTest`, `RestoreSmokeTest`) yokluğunu **aktif olarak iddia
eder**. Dilim 2 bu iddiaları değiştirecek ilk iştir; bu tesadüf değil, "hiçbir
yedek turu ayar yazmaz" invariant'ıdır ve bilinçli olarak güncellenmelidir.

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

İş 4 sırasında şema değişikliği gerektiğini düşünürsen **uygulamadan önce dur ve
kanıtlarıyla bildir.**

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
assertRealApplicationDatabaseUntouched  90 test sınıfında kullanılıyor
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
ComposeSceneHarness                     gerçek Compose sahnesi (desktopTest)
```

Üç smoke turu (`BackupSmokeTest`, `BackupRestoreSmokeTest`, `RestoreSmokeTest`)
ve Dilim 1'in `RetentionSmokeTest`'i, `TemporaryDatabaseDirectory` örneği
tutmadıkları için aynı iddiayı **satır içinde** kurar: gerçek veritabanının var
olup olmadığı turdan önce ölçülür ve sonra karşılaştırılır. Bu yüzden yukarıdaki
90 sayısı yardımcı fonksiyonun kendi sayısıdır, korumanın değil.

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

---

# 30. REGRESYON KOMUTLARI

```bash
./gradlew clean check --rerun-tasks
./gradlew run                       # geçici XDG ile
git diff --check
git status --short
```

Manuel tur:

```bash
XDG_DATA_HOME=/tmp/<gecici>/xdg ./gradlew --no-daemon run
```

UI smoke listesi: açılış, gezinme, tema, klavye, dar pencere, normal kapanış,
arkada process kalmaması.

Manuel turda pencere kapatmak için `wmctrl -i -c` veya gerçek pencere düğmesi
kullanılır; `xdotool windowclose` kullanılmaz. Ekran kilitliyken otomasyon
başlatılmaz; masaüstü veya kilit ekranı yakalanmaz; parola alanına tuş gönderilmez.

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

## Faz 3 — BAŞLADI, 16 İŞTEN 3'Ü BİTTİ; 4'ÜN İLK DİLİMİ YAPILDI

PLAN `18.` — Faz 3 işler listesi.

```text
 1  Geçmiş ekranını tamamla ............................. TAMAM
 2  Import batch rollback ve korumalı geri alma ......... TAMAM (üç dilim)
 3  Sürümlü JSON yedek/dışa aktarma ve geri yükleme ..... TAMAM (dört dilim)
 4  Import ve migration öncesi otomatik snapshot ........ BAŞLADI  ← SIRADAKİ
                                                        (dört dilimden 1'i, §25.2)
 5  CSV görev dışa aktarmayı doğrula ......... özellik var, Faz 3 doğrulama
                                              testleri yazılmadı
 6  Veritabanı migration testlerini oluştur ............. TAMAM
 7  Beklenmeyen kapanış / bozuk import kurtarma ......... YAPILMADI
 8  Klavye, odak, renk dışı etiket, yüksek DPI .... mevcut ekranlar için
                                              büyük ölçüde tamam
 9  Büyük veri setiyle performans testi ...... sorgu sayımı var; yedek
                                              yazma ve okuma için süre/boyut
                                              ölçümü de var (§29), genel
                                              performans kapısı yok
10  Loglama ve anlaşılır hata mesajları ................. YAPILMADI
11  Self-contained Linux dağıtımı ....................... YAPILMADI
12  Garuda/Arch paketi .................................. YAPILMADI
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

> **Faz 3 / İş 4 / Dilim 2: sürümlü `settings.json` ve saklama sayısı ayarı.**
>
> İş 4'ün bütün tasarım kararları alınmıştır (PLAN `14.4.7`–`14.4.13`, özet
> §25.2) ve **Dilim 1 bitmiştir**: adlar, sahiplik kanıtı ve tür başına döngüsel
> saklama motoru yazıldı, hiçbir tetikleyiciye bağlanmadı.
>
> Dilim 2'nin kapsamı:
>
> - `$XDG_CONFIG_HOME/pnp-tracker/settings.json`: `formatVersion` 1 ve
>   `automaticBackupCount` (`1..50`, varsayılan `7`, `0` geçersiz).
> - Bilinmeyen alan yok sayılır; bozuk dosyada varsayılan `7` ile açılır,
>   `Ayarlar` ekranında bu açıkça gösterilir ve **dosyanın üzerine yazılmaz**.
> - Atomik yazma (`AtomicFileWriter`); yazma başarısızsa eski dosya bayt bayt
>   kalır ve çalışma zamanı değeri değişmez.
> - `Ayarlar` ekranında tek yeni alan; aralık dışı değer kabul edilmez.
> - Sayı azaltıldığında dosyalar **hemen silinmez**; ekran bu gecikmeyi kısa bir
>   metinle açıklar.
>
> Dilim 2, Dilim 1'in `rotateAfter(justWritten, keep)` parametresine gerçek
> `automaticBackupCount`'u bağlayacak yerdir; ama rotation'ı **tetiklemez** —
> tetikleyiciler Dilim 3 ve 4'tedir.
>
> Dikkat: `settings.json`'ın yokluğunu **aktif olarak iddia eden** testler var
> (`AppDirectoryInitializerTest`, üç smoke turu ve `RetentionSmokeTest`). Bunlar
> "hiçbir yedek turu ayar yazmaz" invariant'ıdır ve Dilim 2'de bilinçli olarak
> güncellenmelidir; sessizce silinmemelidir.
>
> Sonra: Dilim 3 (her onay öncesi snapshot + yarış koruması) → Dilim 4
> (migration seti + açılış kapısı).
>
> **Dilim 4 bitene kadar gerçek uygulama normal kullanıcı XDG'siyle
> açılmamalıdır** (§0): bugün açılırsa migration snapshot'sız çalışır.
>
> Sonraki bağlayıcı sıra PLAN'ın kendi sırasıdır: İş 4 → 7 → 9 + 5 → 10 →
> 11-13 → 14-16.

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

## R5 — Sürüm numarası ikiye ayrılabilir  *(paketlemede)*

`AppInfo.kt` içinde `"0.1.0"` elle yazılıdır ve Gradle proje sürümüne bağlı değildir.
Paketleme yapılandırıldığında paket sürümüyle sapabilir.

## R6 — Bu makinede doğrulanamayan madde

PLAN `18.` Faz 3 (İş 13 ve testleri) "temiz Garuda ortamında kurulum, açılış, veri dizini, güncelleme ve
kaldırma testi" ayrı bir temiz ortam gerektirir.

## R7 — Paketleme yapılandırması yok

`app/build.gradle.kts` içinde `compose.desktop { application { mainClass } }` dışında
bir şey yok. `nativeDistributions` bloğu tanımlı olmadığı için `packageDeb` /
`packageRpm` / `packageAppImage` görevleri **hiç oluşmuyor**. `LICENSE` dosyası ve
`.github/` dizini de yok.

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

## R10 — Gerçek veritabanı korumasız bir migration'a açık  *(AÇIK — Dilim 4'e kadar)*

Gerçek kullanıcı veritabanı şema **v3**'tedir (§0), kodun şema sürümü ise 8'dir.
Uygulamanın normal XDG diziniyle bir sonraki açılışı, v3 → v8 migration zincirini
**snapshot olmadan** çalıştırır: PLAN `14.4.10`'un açılış kapısı henüz yoktur.

```text
etki       migration'da bir hata olursa geri dönüş yolu yok
olasılık   Migration3To4 yalnız renk taşıyan bir DB'yi temiz göç ettirir ve
           gözlemde kullanıcı verisi görünmüyor — fakat bu bir GARANTİ DEĞİLDİR;
           DB açılmadığı için içeriği kesin olarak bilinmiyor
azaltma    GEÇİCİ KURAL: Dilim 4 bitene kadar gerçek uygulama normal kullanıcı
           XDG'siyle AÇILMAZ. Geliştirme ve manuel turlar geçici XDG kullanır
kapanış    Dilim 4 (açılış kapısı + migration snapshot seti) tamamlandığında
```

## R11 — `VACUUM INTO`'nun bu kurulumdaki davranışı ölçülmedi  *(AÇIK — Dilim 4'te ölçülecek)*

Migration öncesi ham klon için `VACUUM INTO` kullanılmasına izin verilmiştir
(PLAN `14.4.9`). Statik olarak doğrulananlar:

```text
gömülü SQLite      3.50.1  → VACUUM INTO (3.27+) sürüm olarak mevcut
OMIT_VACUUM        derleme seçeneklerinde YOK
erişim yolu        sıradan SQL → SQLiteConnection.prepare()/step() ile ulaşılır
sqlite3_backup_*   semboller .so içinde var ama JNI'ye BAĞLANMAMIŞ → ERİŞİLEMEZ
sqlite3_serialize  iz yok → ERİŞİLEMEZ
açılış bayrakları  SQLITE_OPEN_READONLY ve SQLITE_OPEN_NOFOLLOW mevcut
```

**Ölçülmemiş ve varsayılmayacak olan:** sıcak bir WAL taşıyan bir veritabanının
salt okunur açılışı. SQLite bu durumda `-shm` kurtarma denemesi yapar ve salt
okunur bir bağlantı `SQLITE_READONLY_RECOVERY` ile düşebilir. Dilim 4'ün ilk
testi bu olmalıdır; davranış varsayılmaz, ölçülür.

Ayrıca **yasak olan**, hiçbir koşulda denenmeyecek alternatif: veritabanı, `-wal`
ve `-shm` dosyalarını sırayla kopyalamak. Üçü arasında atomiklik yoktur ve
kopyalama sırasında araya giren bir checkpoint tutarsız bir üçlü bırakır.

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
- İş 4'ün (otomatik snapshot + döngüsel saklama) BÜTÜN TASARIM KARARLARI
  ALINMIŞTIR: PLAN 14.4.7-14.4.13, özet §25.2. Yeniden tartışma.
  Sıradaki bağlayıcı iş: İş 4 / DİLİM 1 — otomatik yedek adları, sahiplik
  kanıtı ve tür başına döngüsel saklama motoru. Hiçbir tetikleyiciye bağlanmaz.
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
- GEÇİCİ KURAL: Dilim 4 bitene kadar gerçek uygulamayı normal kullanıcı XDG'siyle
  AÇMA. Gerçek DB şema v3'tedir ve bir sonraki normal açılış v3 -> v8 migration'ı
  snapshot'sız tetikler (§0).
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
[ ] Gerçek DB hash + boyut + mtime önce/sonra aynı
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
- Kullanıcı bütün verisinin sürümlü, deterministik ve checksum'lı bir JSON
  yedeğini `Ayarlar` ekranından istediği yere atomik olarak kaydedebilir.
- Ve o yedeği **geri yükleyebilir**. Dosya hiçbir şeyine güvenilmeden okunur;
  yalnız geçemeyen bir dosya için yıkıcı onay hiç gösterilmez; onaydan sonra
  mevcut verinin zorunlu güvenlik yedeği `backups/` altına yazılır; canlı
  veritabanı tek transaction'da yedeğin yerine geçer; ve her başarısızlık
  yolunda veri işlemden önceki hâlinde kalır. Geri yüklenmiş bir `CONFIRMED`
  içe aktarma hâlâ geri alınabilir.

Kalan iş ağırlıklı olarak **dayanıklılık, kurtarma, paketleme ve yayına
hazırlıktır**: otomatik snapshot, kurtarma akışı, loglama, performans kapısı,
Linux paketi, belgeler, lisans ve CI.

Bunların ilki — **sürümlü JSON yedek ve geri yükleme** — dört atomik dilimde
**tamamlanmıştır**. Biçim, kapsam, doğrulama hattı, restore mimarisi (A′),
güvenlik yedeği ve dört dilim PLAN `14.4` ile §25.1'de yazılıdır.

Sıradaki iş **otomatik snapshot**'tır (PLAN Faz 3 / iş 4) ve bu belgeyle birlikte
**bütün tasarım kararları alınmıştır**; bağlayıcı metni PLAN `14.4.7`–`14.4.13`,
özeti §25.2'dedir. Kod henüz yazılmamıştır.

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

İş dört atomik dilimde uygulanmaktadır. **Dilim 1 bitmiştir:** otomatik
yedeklerin adları, sahipliklerinin iki bağımsız kanıtla doğrulanması ve üç
bağımsız kota için döngüsel saklama motoru yazıldı. Motor hiçbir tetikleyiciye
bağlı değildir ve sildiği tek şey, adının kalıbıyla **ve** ilk baytlarıyla
kendisinin olduğunu gösterebildiği dosyalardır; manuel yedekler, bilinmeyen
dosyalar, bağlantılar ve eksik eşli migration setleri erişiminin dışındadır.
**Dilim 2** (`settings.json` ve saklama sayısı ayarı) sıradaki bağlayıcı iştir.

> **Geçici kural:** Dilim 4 tamamlanana kadar gerçek uygulama normal kullanıcı
> XDG'siyle açılmamalıdır. Gerçek veritabanı şema v3'tedir ve bir sonraki normal
> açılış, henüz var olmayan açılış kapısı olmadan bir v3 → v8 migration'ını
> tetikler (§0).

En önemli kural:

> **Eski sohbetleri tekrar okumaya çalışmak yerine önce PLAN.md + bu dosyayı oku,
> sonra mevcut repo durumunu doğrula.**
