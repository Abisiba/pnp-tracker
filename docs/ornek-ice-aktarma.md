# Örnek içe aktarma belgesi

Bu belge, uygulamanın **gerçekten kabul ettiği** dosya biçimlerini anlatır.
Buradaki kurallar uygulamanın okuyucusundan alınmıştır; örnek CSV dosyası
depoda durur ve olduğu gibi içe aktarılabilir:

- [`ornek-ice-aktarma.csv`](ornek-ice-aktarma.csv)

İçe aktarma hiçbir zaman kendiliğinden oyun, hücre veya görev oluşturmaz. Dosya
okunur, ham hücreler taslak olarak kaydedilir ve her şey sizin onayınızla yazılır.

## İki biçim, tek yol

| | XLSX | CSV |
| --- | --- | --- |
| Sütunlar | Yedi sütunlu referans düzeni, başlık satırından tanınır | `game`, `source_type`, `raw_text` başlıkları |
| Sayfa | Birden çok çalışma sayfası olabilir, hangisinin okunacağı sorulur | Tek mantıksal sayfa; sayfa adı dosya adıdır |
| Renk | Hücre dolgusu ve zengin metin renkleri okunur | Renk yoktur |
| "Tamamlandı" ipucu | Yeşil hücre ipucu olabilir | Yeşil hücre ipucu **hiç oluşmaz** |
| Formül | Hücrenin görünen değeri okunur | Her hücre düz metindir |
| Kodlama | Excel'in kendi kodlaması | Katı UTF-8; tek BOM atılır |

Her iki biçim de aynı yere varır: ham hücreler → taslak → inceleme → onay.

## CSV kuralları

```text
zorunlu başlıklar : game, source_type, raw_text
sütun sırası      : serbest; fazladan sütunlar yoksayılır
ayırıcı           : virgül veya noktalı virgül (başlık satırından bulunur)
kodlama           : UTF-8; baştaki tek BOM atılır
boş satır         : atlanır
hücre metni       : kırpılmaz, olduğu gibi saklanır
aynı satırlar     : tekilleştirilmez
= + - @ ile başlayan hücreler : düz metin olarak kalır
```

`source_type` sütununda şu değerler geçerlidir (büyük/küçük harf ve boşluk fark
etmez):

| Değer | Anlamı | Excel'deki karşılığı |
| --- | --- | --- |
| `GAME` | Satır oyunun adını taşır | Oyun |
| `THREE_D` | 3D baskı işi | 3D Print |
| `CARD` | Kart / laminasyon işi | Laminasyon |
| `BOARD` | Mukavva işi | Mukavva |
| `SPECIAL` | Diğer özel parçalar | Özel |
| `MISSING` | Eksik parça notu | Eksik |
| `BORROWED` | Ödünç verilen/alınan parça | Ödünç Parçalar |

Excel başlıklarının kendileri de yazılabilir: `source_type` sütununa `Laminasyon`
yazmak `CARD` demekle aynıdır.

## Örnek CSV

```csv
game,source_type,raw_text
Harmonies,GAME,Harmonies
Harmonies,THREE_D,"15 KIRMIZI**, 19 YEŞİL"
Harmonies,CARD,54 oyun kartı
Harmonies,BOARD,"2 oyun tahtası**"
Harmonies,SPECIAL,Kumaş torba
Harmonies,MISSING,3 sarı token eksik
Harmonies,BORROWED,Zar seti (Ali)
Ark Nova,GAME,Ark Nova
Ark Nova,THREE_D,"12 SİYAH ağaç, 8 BEYAZ çadır"
Ark Nova,CARD,"=1+1 yazan kart, düz metin olarak kalır"
Ark Nova,SPECIAL,Özel: skor defteri
```

Bu dosyadan iki oyun ve dokuz ham hücre çıkar. Hiçbiri onaylanana kadar
veritabanına görev olarak yazılmaz.

### Örnekteki ayrıntılar

- **Türkçe karakterler**: `YEŞİL`, `SİYAH`, `Özel`, `Kumaş` olduğu gibi korunur;
  büyük/küçük harf dönüşümü yapılmaz.
- **Adet önerisi**: metin bir sayıyla başlıyorsa (`15 KIRMIZI**`) o sayı adet
  önerisi olur. Metnin ortasındaki sayılar adet sayılmaz: `Ticket to Ride 1910`
  bin dokuz yüz on adet demek değildir. `0` adet sayılmaz.
- **`**` işareti**: "bu kısım bitti" anlamına gelen ipucudur. Metinde olduğu gibi
  saklanır, inceleme ekranında gösterilmez. Tek `*` işaret değildir; `****` iki
  işarettir.
- **Renk adları**: `KIRMIZI`, `YEŞİL`, `SİYAH`, `BEYAZ` gibi tanıdık adlar renk
  önerisi üretir. Öneridir; rengi siz seçersiniz.
- **Virgül içeren hücre**: tırnak içine alınır (`"15 KIRMIZI**, 19 YEŞİL"`).
- **Formül gibi görünen hücre**: `=1+1 yazan kart…` dört karakteriyle birlikte
  düz metin kalır; hiçbir yerde hesaplanmaz.
- **Boş hücre**: `raw_text` boş bırakılamaz (aşağıya bakın). Bir oyunda bir sütun
  yoksa o satırı hiç yazmayın.

## Hatalı satır örnekleri

Uygulama hatayı satır numarasıyla söyler ve **hiçbir şey kaydetmez** — dosyanın
son satırındaki bir hata bile dosyanın tamamını geçersiz kılar.

| Örnek | Sonuç |
| --- | --- |
| `Harmonies,THREE_D` (eksik alan) | Satır, başlıkla aynı sayıda alan taşımıyor: tırtıklı satır reddi |
| `Harmonies,,15 KIRMIZI` | Zorunlu değer boş: `source_type` boş olamaz |
| `Harmonies,3D_YAZICI,15 KIRMIZI` | Tanınmayan `source_type`; uygulama tahmin etmez |
| `game,game,raw_text` başlığı | Aynı zorunlu sütun iki kez |
| `oyun,tur,metin` başlığı | Zorunlu sütun eksik |
| `Harmonies,THREE_D,"15 KIRMIZI` | Kapanmamış tırnak |
| `Harmonies,THREE_D,15 "KIRMIZI"` | Tırnakla başlamayan alanın içinde tırnak |
| Hem virgül hem noktalı virgülle okunabilen başlık | İki ayırıcı da geçerli: dosya reddedilir, tahmin yapılmaz |

## Formül enjeksiyonu koruması — kullanıcı açısından

Bir hücreye `=`, `+`, `-` ya da `@` ile başlayan bir şey yazdıysanız:

- **İçe aktarırken** bu metin olduğu gibi saklanır. Uygulama onu hesaplamaz,
  değiştirmez ve formül olarak görmez.
- **Dışa aktarırken** (Görevleri CSV'ye aktar) uygulama dosyaya yazarken metnin
  başına tek tırnak ekler. Böylece dosyayı Excel veya LibreOffice ile açtığınızda
  program onu formül sanıp çalıştırmaz. Uygulamadaki metniniz değişmez; koruma
  yalnız dosyadadır.

## XLSX düzeni

Referans çalışma sayfası yedi sütunludur ve başlık satırından tanınır:

```text
Oyun | 3D Print | Laminasyon | Mukavva | Özel | Eksik | Ödünç Parçalar
```

- Oyun adı sütununda yazan satır o satırdan sonraki işlerin hangi oyuna ait
  olduğunu belirler.
- Hücre renkleri okunur; yeşil bir oyun hücresi "bu oyun tamamlanmış olabilir"
  ipucu üretir. İpucu onay bekler, kendiliğinden uygulanmaz.
- Zengin metin içindeki renkli parçalar korunur.
- Gizli sayfalar da seçilebilir; uygulama sayfanın gizli olduğunu söyler.
- Başlık satırı tanınmazsa dosya reddedilir ve hiçbir hücre kaydedilmez.

Depodaki `app/src/desktopTest/resources/sample-import.xlsx` dosyası bu düzende,
anonimleştirilmiş küçük bir örnektir ve doğrulama turlarında kullanılabilir.

## İçe aktardıktan sonra

1. **İçe Aktarma** ekranında taslağı açın.
2. Her ham hücrede görev olacak kısmı seçin; havuzu, adedi ve rengi belirleyin.
3. **Onayla** deyin. Onaydan hemen önce uygulama kendiliğinden yedek alır.
4. Sonuç beklediğiniz gibi değilse **Onaylanmış içe aktarmalar** listesinden
   **Geri al** ile tamamını geri alabilirsiniz (koşulları kullanım kılavuzundadır).
