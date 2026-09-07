# PNP — MASTER CONTEXT

> Bu dosya, yeni bir AI/Claude Code oturumunun eski sohbetleri baştan okumadan doğru
> çalışma bağlamını kurabilmesi içindir: **mevcut mimari, veri modeli, invariant'lar,
> UX kuralları ve geliştirmenin bulunduğu nokta.**
>
> **PLAN.md tek yetkili kaynaktır.** Bu dosya PLAN.md'nin yerine geçmez, onu özetler ve
> repo durumuyla ilişkilendirir. Çelişki hâlinde PLAN.md kazanır.
>
> **Son güncelleme:** Faz 3 / İş 2'nin ikinci dilimi (geri alma motoru ve
> geçmiş olayları) tamamlandıktan sonra.
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
HEAD (bu commit öncesi): 3e91d4f2cf098a76ab20a3df297b7e0a199a6ffa
önceki commit         : feat(import): remember cells before confirmation
working tree          : temiz
Room şema sürümü      : 8   (bu commit'te DEĞİŞMEDİ)
şema dosyaları        : 1.json … 8.json  hepsi bayt bayt aynı
test durumu           : 2858 test / 0 failure / 0 error / 0 skipped
üretim kodu           : 239 dosya
test kodu             : 180 dosya
```

**Bu commit Faz 3 / İş 2'nin ikinci dilimidir.** Geri alma motorunu, üç yeni
geçmiş olayını ve bunların Türkçe geçmiş satırlarını ekler. Kullanıcı geri almayı
henüz **başlatamaz**: düğme, onaylanmış içe aktarma listesi ve onay penceresi
üçüncü dilimdedir. Bugün görülebilen tek fark, bir içe aktarma onaylandığında
Geçmiş ekranında beliren `İçe aktarma onaylandı…` satırıdır.

Yeni enum değerleri TEXT olarak saklandığı için **şema sürümü 8 kalmıştır** ve
`8.json` değişmemiştir.

`PLAN.md` bu turda **değiştirilmemiştir.** Bir önceki tur onu kullanıcının açık
talimatıyla düzenlemişti; varsayılan kural yine dokunmamaktır.

## Doğrulama hash'leri

```text
PLAN.md  66a8e42aafc7e4894182b6beaac2fcd1c0bed84db8d3d202f02a58c8fdf9525c
         (bu commit PLAN'ı değiştirmez; değer bir önceki commit'ten aynen gelir)

1.json   7cafd48fb4b06ec1da00b3f15f4335aae46fb8b40fc57926cde442dda515a724
2.json   e596d1bccc5054bf4442faff43ebdfff03ff4c5024d4afc1d8e9ddad2ed3f11a
3.json   5acd37f74b2898ce6f0577149ae0dc609be8ca141292b69b29b5157eb14f5da0
4.json   1b1c5613b5eab3236de96abb1083a9256b39b0930ec5a9e520cf4777670219a0
5.json   e504a0654d3db06b2be353b8fbbcdb6dfed2230f4c53e99c9280ee8f0f99d017
6.json   aa73e89137f4b5585e4ed0c0e7bbab38aad841897cd2ce16b910c4c4cc4e2277
7.json   690843ebbfe4d61b33bf7db2e35b3a5038ba0206dc6b4ca5484311312af298b7
8.json   498dfef21e479209c793f731b3593dae37cf688ae7bf275775eb744255913480
         (bu commit'te eklendi; Room'un kendi ürettiği şema)

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
`assertRealApplicationDatabaseUntouched` yardımcı fonksiyonuyla **70 test sınıfında**
uygulanmaktadır.

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
```

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
```

Sıra PLAN 12.1'in sırasıdır. `Geçmiş` koşulsuzdur: içi boşken de sidebar'dadır ve
boşluğunu ekranda söyler — `Özel` havuzun gizlenme kuralı ona uygulanmaz.

## PLAN'da tanımlı, henüz yapılmamış olanlar

```text
Ayarlar    (PLAN 12.1 satır 800)
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
assertRealApplicationDatabaseUntouched  73 test sınıfında kullanılıyor
CommittedSchema                         eski sürümleri commit'li JSON'dan kurar
LegacyRowFixtures                       v1…v6 satır yazıcıları
                                        (v6 raw block = v7 raw block; şema aynı)
CountingSqliteDriver                    gerçek sürücü seviyesinde ifade sayımı
FailingSqliteDriver                     enjekte edilen depolama hataları
ComposeSceneHarness                     gerçek Compose sahnesi (desktopTest)
```

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

## Faz 3 — BAŞLADI, 16 İŞTEN ~3'Ü

PLAN `18.` — Faz 3 işler listesi.

```text
 1  Geçmiş ekranını tamamla ............................. TAMAM
 2  Import batch rollback ve korumalı geri alma ... dilim 1-2 TAMAM,
                                              dilim 3 (arayüz) YAPILMADI
 3  Sürümlü JSON yedek/dışa aktarma ve geri yükleme ..... YAPILMADI
 4  Import ve migration öncesi otomatik snapshot ........ YAPILMADI
 5  CSV görev dışa aktarmayı doğrula ......... özellik var, Faz 3 doğrulama
                                              testleri yazılmadı
 6  Veritabanı migration testlerini oluştur ............. TAMAM
 7  Beklenmeyen kapanış / bozuk import kurtarma ......... YAPILMADI
 8  Klavye, odak, renk dışı etiket, yüksek DPI .... mevcut ekranlar için
                                              büyük ölçüde tamam
 9  Büyük veri setiyle performans testi ...... sorgu sayımı var,
                                              süre/bellek ölçümü yok
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

`TASK_RESTORED` ve `GAME_RESTORED` de yazılmıyor (§15, "yazıcısı olmayan
türler"). Ekran bu üçünü **uydurmuyor**; yazan yol geldiğinde kendiliğinden
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

> **Faz 3 / İş 2 dilim 3: geri alma arayüzü.**
>
> Ürün kararları `PLAN.md` `11.4.4`'tedir ve yeniden tartışılmaz. Motor hazır ve
> tamamen testli; eksik olan yalnız onu çağıran ekran. Repo tarafındaki
> ayrıntılar §19'dadır.

### İş 2'nin üç atomik dilimi

```text
1  Room v8 ve hücre anlık görüntüsü ....................... TAMAM
   import_batch_cells, Migration7To8, 8.json, onayın document_before yazması.
   Davranış değişmedi; kullanıcı hiçbir fark görmez.

2  Rollback motoru ve geçmiş olayları ..................... TAMAM
   Engelleme denetimi (dokunulmuş görev VEYA değişmiş hücre), tek transaction,
   tombstone'lar, hücre geri yüklemesi, postcondition;
   IMPORT_CONFIRMED / IMPORT_ROLLED_BACK / TASK_ROLLED_BACK ve Türkçe geçmiş
   cümleleri. Geri almayı başlatan arayüz yok.

3  Arayüz .............................................. SIRADAKİ
   Onaylanmış içe aktarma listesi, geri alma düğmesi, onay ve engelleme
   ekranları. Engelleme listesi için gereken yapısal veri hazırdır:
   ImportRollbackPreview.blockedTasks / blockedCells görev adını, oyun adını ve
   sütun türünü taşır.
```

Sıra bağlayıcıdır: 1 olmadan 2 tam hücre geri yüklemesi yapamaz, 2 olmadan 3'ün
çağıracağı bir şey yoktur.

### Dilim 2 ne bıraktı, dilim 3 neyi devralıyor

```text
bıraktığı                                    dilim 3'ün kullanacağı yer
------------------------------------------   ------------------------------------
ImportRollback (arayüz) + ImportRollbackStore  controller'ın çağıracağı yüzey
previewRollback → ImportRollbackPreview        onay penceresinin sayıları
blockedTasks / blockedCells                    engelleme listesinin içeriği
ImportRollbackFailure (8 değer)                her reddin kendi Türkçe cümlesi
üç geçmiş türü + Türkçe satırları              Geçmiş ekranında zaten görünüyor
```

`ImportRollbackStore` **bilerek `Main.kt`'ye bağlanmamıştır**: onu çağıran bir
ekran yokken bağlamak, kullanılmayan bir üretim yolu bırakmak olurdu (§33 R3).
Dilim 3 hem bağlar hem kullanır.

Dilim 3'ün yazacağı hiçbir şey burada yazılmadı: geri alma düğmesi yok,
onaylanmış batch listesi yok, onay/engelleme penceresi yok.

Sonrasında PLAN'ın bağlayıcı sırası izlenir: İş 3 → 4 → 7 → 9 + 5 → 10 →
11-13 → 14-16.

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

## R3 — Kullanılmayan üretim API'leri  *(orta)*

`TaskDao.softDelete`, `GameDao.softDelete` ve `completePrimaryBatch` üretim kodundan
çağrılmıyor; `TASK_RESTORED` / `GAME_RESTORED` yazılmıyor. Yani **uygulamada bugün
görev/oyun silme ve geri yükleme yolu yoktur.** Bilinçli bir karardır (sırf olay
üretmek için özellik uydurmamak), ama İş 2 bunlara dayanacaktır.

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
- İş 2 (import rollback) üç dilime bölündü; DİLİM 1 ve 2 BİTTİ:
  Room v8 + import_batch_cells, geri alma motoru, üç geçmiş olayı.
- Sıradaki bağlayıcı iş: İş 2 DİLİM 3 — geri alma arayüzü. Motor hazır ve testli;
  ImportRollbackStore henüz Main.kt'ye bağlı değildir, dilim 3 bağlayacaktır.
- İş 2'nin ürün kararları VERİLMİŞTİR ve PLAN 11.4.4'tedir; yeniden tartışma.
  Kısmi rollback yoktur, tek çakışma bütün işlemi engeller, segment kimliğine
  provenance bağlanmaz, anlık görüntüsü olmayan eski batch geri alınamaz.
- PLAN'a satır numarasıyla atıf yapma; bölüm numarası kullan (PLAN 12.15 gibi).

Şimdi:
1. Repo durumunu doğrula (branch/HEAD/temiz ağaç/Room sürümü/PLAN hash/schema hash).
2. PLAN.md'nin ilgili bölümlerini oku.
3. Yalnız o dilimin kapsamını uygula.
4. Testleri çalıştır: ./gradlew clean check --rerun-tasks
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
  Tek bir çakışma bütün işlemi engeller ve hiçbir satır değişmez. Bunu başlatan
  arayüz henüz yoktur.

Kalan iş ağırlıklı olarak **dayanıklılık, yedekleme, kurtarma, paketleme ve yayına
hazırlıktır**: import rollback'in arayüzü, JSON yedek/geri yükleme,
otomatik snapshot, kurtarma akışı, loglama, performans kapısı, Linux paketi,
belgeler, lisans ve CI.

En önemli kural:

> **Eski sohbetleri tekrar okumaya çalışmak yerine önce PLAN.md + bu dosyayı oku,
> sonra mevcut repo durumunu doğrula.**
