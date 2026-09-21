# PnP Üretim Takipçisi — Kullanım Kılavuzu

Bu kılavuz uygulamayı ilk kez kullanan biri içindir. Ekranların ne işe yaradığını
ve günlük işlerin nasıl yapıldığını anlatır.

## PnP Tracker nedir?

PnP (print and play) masa oyunlarını kendiniz üretirken yapılacak işleri takip
eden, tamamen yerel çalışan bir Linux masaüstü uygulamasıdır. 3D baskı, kart
laminasyonu, mukavva kesimi ve özel parçalar için hangi oyunda ne kaldığını
gösterir.

- İnternete çıkmaz, hesap istemez, hiçbir veriyi dışarı göndermez.
- Bütün veriler bilgisayarınızda, sizin kullanıcı dizinlerinizde durur.
- Elinizdeki Excel veya CSV listesini içe aktarabilir, kendi listenizi de
  sıfırdan kurabilirsiniz.

## İlk açılış ve verilerin yeri

Uygulama ilk açıldığında veri dosyasını kendisi oluşturur. Bir şey sormaz.

| Ne | Nerede |
| --- | --- |
| Veritabanı ve yedekler | `$XDG_DATA_HOME/pnp-tracker/` (tanımsızsa `~/.local/share/pnp-tracker/`) |
| Ayarlar | `$XDG_CONFIG_HOME/pnp-tracker/settings.json` (tanımsızsa `~/.config/pnp-tracker/`) |
| Tanılama kayıtları | `$XDG_STATE_HOME/pnp-tracker/logs/` (tanımsızsa `~/.local/state/pnp-tracker/logs/`) |

Bilmeniz yeterli olan üç şey:

- Uygulamayı kaldırmak bu dizinleri silmez; verileriniz durur.
- Uygulamanın kurulduğu klasöre (taşınabilir arşiv ya da `/opt/pnp-tracker`)
  hiçbir şey yazılmaz.
- Verinizi taşımak isterseniz yol, uygulamadan yedek alıp yeni makinede geri
  yüklemektir (aşağıda).

## Ekranlar

Sol kenardaki bölümler: **Ana Sayfa**, **Oyunlar**, dört havuz (**3D Baskı**,
**Kartlar**, **Mukavva**, **Özel**), **İçe Aktarma**, **Geçmiş**, **Renkler**,
**Ayarlar**.

## Oyun oluşturma

1. **Oyunlar** bölümüne geçin.
2. **Yeni oyun oluştur** düğmesine basın.
3. Oyunun adını yazın ve **Oyunu kaydet** deyin.

Oyun tablosunda her oyun bir satırdır. Satırın üstündeki **Görünüm** seçicisiyle
*Devam Eden*, *Tamamlanan* ve *Tümü* arasında geçebilirsiniz.

Bir oyunu bitirdiğinizde satırındaki **Tamamlandı olarak işaretle** ile
işaretleyebilirsiniz. Bu yalnızca sizin kararınızdır: görevleri ve hücreleri
değiştirmez.

## Hücre ve görev yapısı

Her oyunun beş sütunu (hücresi) vardır: **3D Baskı**, **Kart**, **Mukavva**,
**Özel** ve **Notlar**. İlk dördü aynı adlı havuzları besler; **Notlar** sütunu
serbest metindir ve görev tutmaz. Hücrenin içi yazıdır; o yazının içinde bazı
kelimeler **görev** olarak işaretlenir.

(Excel dosyasındaki başlıklar farklıdır: orada aynı sütunlar *3D Print*,
*Laminasyon*, *Mukavva*, *Özel* diye geçer — bkz. örnek içe aktarma belgesi.)

- **Hücre**: oyunun bir sütunundaki yazının tamamı.
- **Görev**: o yazının içinden seçilmiş, takip edilen iş. Rengi, adedi ve üretim
  aşamaları olabilir.

Bir görevi açmak için hücredeki görev parçasına tıklayın ya da klavyeyle üstüne
gelip Enter'a basın. Açılan menüde **Düzenle**, **Tamamla**, **Eksik/hatalı
bildir** ve **Görevi metne dönüştür** vardır.

## XLSX ve CSV içe aktarma

**İçe Aktarma** bölümünde **Excel veya CSV dosyası seç** ile başlarsınız.

- **Excel (.xlsx)**: yedi sütunlu referans düzeni beklenir (Oyun, 3D Print,
  Laminasyon, Mukavva, Özel, Eksik, Ödünç Parçalar). Hücre renkleri ve zengin
  metin okunur.
- **CSV (.csv)**: `game`, `source_type` ve `raw_text` sütunları zorunludur.
  Ayırıcı virgül ya da noktalı virgül olabilir.

Biçimlerin tam kuralları, örnek dosya ve hata örnekleri için
[örnek içe aktarma belgesine](ornek-ice-aktarma.md) bakın.

Dosyayı seçtiğinizde uygulama bir **özet** gösterir: kaç ham hücre kaydedilecek,
kaç oyun adı hücresi algılandı, hangi sütunda kaç hücre var. Bu adımda **hiçbir
oyun, hücre veya görev oluşturulmaz**. **Taslak olarak kaydet** dediğinizde
yalnız ham hücreler taslak olarak saklanır.

Aynı dosyayı daha önce içe aktardıysanız uygulama bunu söyler ve yine de yeni bir
taslak oluşturmak isteyip istemediğinizi sorar.

## Taslakları inceleme, düzenleme, onaylama ve kaldırma

Onaylanmamış içe aktarmalar **Devam eden içe aktarmalar** listesinde durur.
Kaynak dosya silinmiş ya da değişmiş olsa bile kaldığınız yerden devam
edebilirsiniz.

İnceleme ekranında her ham hücre için:

- metnin içinden görev olacak kısmı seçersiniz;
- görevin havuzunu, adedini ve rengini belirlersiniz;
- uygulamanın önerilerini (baştaki sayı adet olabilir, `**` "bitti" anlamına
  gelebilir, tanıdık renk adları) kabul eder ya da değiştirirsiniz.

Öneriler yalnızca öneridir: hiçbiri siz onaylamadan hiçbir şeye uygulanmaz.

**Onayla** dediğinizde görevler, hücre metinleri ve oyunlar tek bir işlemde
yazılır. Onaydan hemen önce uygulama kendiliğinden bir yedek alır; yedek
alınamazsa onay hiç başlamaz.

Bir taslağı istemiyorsanız **Kaldır** ile silebilirsiniz; bu yalnız o taslağın
satırlarını siler.

Kayıtları birbiriyle uyuşmayan taslaklar ayrı bir bölümde, uyarıyla ve yalnız
**Kaldır** seçeneğiyle gösterilir. Böyle bir taslak onaylanamaz.

## Onaylanmış içe aktarmayı geri alma

**Onaylanmış içe aktarmalar** listesinden **Geri al** ile, bir içe aktarmanın
oluşturduğu her şeyi tek seferde kaldırabilirsiniz. Uygulama önce ne olacağını
gösterir: kaç görev kaldırılacak, kaç hücre eski metnine dönecek.

Geri alma **tamamı ya da hiçbiri**dir; tek tek görev seçilemez. Şu durumlarda
reddedilir ve hiçbir şey değişmez:

| Durum | Neden |
| --- | --- |
| Oluşturduğu görevlerden biri sonradan düzenlendi | Sizin emeğinizi geri almamak için |
| Yazdığı hücrelerden birinin metni sonradan değişti | Aynı nedenle |
| İçe aktarma zaten geri alınmış | İkinci kez geri alınamaz |
| Hücrelerin önceki metni kaydedilmemiş (eski içe aktarma) | Güvenli geri alma kanıtlanamaz |
| İçe aktarmanın kayıt izi eksik | Hangi görevlerin ona ait olduğu güvenle belirlenemez |

Oyunların "tamamlandı" işaretleri geri alınmaz; isterseniz oyun tablosundan
kendiniz kaldırırsınız.

## Görev ilerlemesi, tamamlanma ve metne dönüştürme

- **Tamamla / Yeniden aç**: görevin bittiğini işaretler ya da geri alır.
- **Eksik/hatalı bildir**: 3D baskıda başarısız çıkan adedi kaydeder; daha sonra
  **Eksik giderildi** ile kapatırsınız.
- **Aşamalar**: kart ve mukavva görevlerinde sıradaki üretim aşaması (baskı,
  laminasyon, kesim) ve kaç adedin o aşamayı geçtiği tutulur.
- **Görevi metne dönüştür**: kelimeyi hücrede düz metin olarak bırakır; rengi,
  adedi ve aşamaları artık görev olarak tutulmaz. Bu işlem geri alınamaz; üretim
  geçmişi silinmez ama görev listelerinden çıkar.

## Arama ve havuz filtreleri

Oyun tablosunda arama kutusu, oyun adı ve hücre metinlerinde arar. Havuz
ekranlarında görevler renge ve duruma göre gruplanır:

- **Renk seçilecek**, **Tek renkli**, **Tek öge çok renk** bölümleri;
- her grup için görev sayısı, toplam adet, eksik ve hatalı kayıt sayısı.

Arama ve filtreler yalnız ekranda ne gördüğünüzü değiştirir; verilerinize ve dışa
aktarılan dosyaya dokunmaz.

## CSV dışa aktarma

**Ayarlar → Görevleri CSV'ye aktar** ile bütün görevleri tek dosyaya yazarsınız.

- Ekrandaki arama ve filtreler dosyanın kapsamını değiştirmez: her zaman bütün
  görevler yazılır.
- Sütunlar: `game, column, task, pool, colors, required_quantity, status, notes`.
- Dosya UTF-8'dir ve Excel'in doğru açması için BOM ile başlar.
- Aynı veriden her zaman aynı dosya üretilir.
- `=`, `+`, `-`, `@` ile başlayan metinlerin başına, tablo programları onları
  formül sanmasın diye tek tırnak eklenir. Veritabanınızdaki metin değişmez.

## Manuel yedek oluşturma

**Ayarlar → Yedek oluştur**: bütün veriniz tek bir `.json` dosyasına yazılır.
Dosyanın adı varsayılan olarak `pnp-yedek-<tarih>.json` gelir ve seçtiğiniz
klasöre kaydedilir — harici bir disk de seçebilirsiniz.

Yedek; oyunları, hücre metinlerini, görevleri, renkleri, üretim ilerlemesini,
geçmişi ve içe aktarma kayıtlarını içerir.

## Yedekten geri yükleme ve güvenlik yedeği

**Ayarlar → Yedekten geri yükle** ile bir yedek dosyası seçersiniz. Uygulama:

1. dosyayı denetler (bozuk, eksik ya da başka bir uygulamanın dosyasıysa reddeder
   ve hiçbir şeye dokunmaz);
2. ne olacağını sorar: seçtiğiniz yedek **bütün verinin yerine geçer**;
3. **Geri yükle** derseniz önce mevcut verinizin güvenlik yedeğini
   `pnp-oncesi-…json` adıyla yedek klasörüne yazar;
4. ancak güvenlik yedeği yazıldıktan sonra geri yüklemeyi yapar.

Bir şey ters giderse veriniz işlemden önceki hâlinde kalır ve ekran size güvenlik
yedeğinin adını söyler.

Kayıtları kendi içinde çelişen bir yedek, soru sorulmadan önce reddedilir;
verileriniz olduğu gibi kalır.

## Otomatik yedek sayısı ayarı

Uygulama bazı işlemlerden önce kendiliğinden yedek alır: içe aktarma onayından
önce, geri yüklemeden önce ve veri dosyası yeni sürüme taşınmadan önce.

**Ayarlar → Otomatik yedeklerin saklanması** bölümünde kaç tanesinin saklanacağını
belirlersiniz (1–50, varsayılan 7). Sayı üç tür için ayrı ayrı uygulanır. Kendi
aldığınız yedekler bu sayıya dâhil değildir ve hiçbir zaman kendiliğinden
silinmez. Sayıyı azaltmak dosyaları o anda silmez.

## Beklenmeyen kapanış sonrası

Bilgisayar kapanır ya da uygulama beklenmedik biçimde sonlanırsa, yeniden
açtığınızda kaldığınız yerden devam edersiniz. Yarım kalan bir yazma işlemi ya
tamamen yazılmış ya da hiç yazılmamış olur; ikisinin arası olmaz. Onaylanmamış
bir içe aktarma taslağı **Devam eden içe aktarmalar** listesinde durur.

Uygulama bir "kurtarma" ekranı açmaz, işaret dosyası bırakmaz ve sizden bir şey
onarmanızı istemez.

## "Veri dosyanızda bir hasar bulundu" ekranı görülürse

Uygulama her açılışta veri dosyasını hızlıca denetler. Dosya hasarlıysa
**PNP açılamadı** başlıklı bir ekran gösterir ve veri dosyasını **açmaz**.

Bu durumda:

1. Veri dosyasını ve yedek klasörünüzü **silmeyin, taşımayın, onarmaya
   çalışmayın**. Uygulama da bunların hiçbirini yapmaz.
2. Yedek klasörünüzdeki yedekler olduğu gibi durur.
3. Elinizdeki en yeni yedekle yeni bir kurulumda devam edebilirsiniz; hasarlı
   dosyayı silmeden önce mutlaka bir kopyasını saklayın.
4. Yardım isterken ekrandaki cümleyi ve aşağıdaki tanılama kaydının son
   satırlarını iletmek yeterlidir.

## Tanılama kayıtları

Bir şey ters gittiğinde uygulama kısa, teknik olmayan bir kayıt satırı yazar:

```
$XDG_STATE_HOME/pnp-tracker/logs/
```

Bu kayıtlar en çok 5 dosya × 1 MiB yer kaplar ve eskiyen dosya kendiliğinden
silinir. İçinde **oyun adı, görev metni, not, dosya adı, yol, kullanıcı adı,
kimlik numarası, SQL ya da hata mesajı bulunmaz**: yalnız ne tür bir sınırın
aşıldığı, hangi işlemde olduğu ve hatanın sınıf adı yazılır. Başarılı bir
kullanımda hiç satır yazılmaz.

## Klavye ve erişilebilirlik

- **Tab** ve **Shift+Tab** ile bütün ekranlarda dolaşabilirsiniz; odak kenar
  çubuğuna geri döner, hiçbir yerde takılıp kalmaz.
- **Enter** veya **Space** seçili öğeyi açar; tabloda **Enter** ya da **F2**
  hücreyi düzenlemeye başlar.
- **Esc** açık bir menüyü, paneli veya soruyu kapatır ve hiçbir şeyi değiştirmez.
- **Ctrl+Enter** düzenleme panellerinde kaydeder.
- Geri alınamaz sorularda odak **Vazgeç** üzerinde başlar.
- Uygulama dar pencerede (640×460) ve büyük sistem yazı tipinde de kullanılabilir;
  bilgi yalnız renkle anlatılmaz, her durumun yazısı vardır.

## Linux taşınabilir arşivini çalıştırma

Java kurmanız gerekmez; çalışma ortamı arşivin içindedir.

```bash
tar -xzf pnp-tracker-<sürüm>-linux-x86_64.tar.gz
cd pnp-tracker-<sürüm>
./bin/pnp-tracker
```

Arşivi istediğiniz klasöre açabilirsiniz. Uygulama açıldığı klasöre yazmaz;
verileriniz yine XDG dizinlerinize gider. Klasörü silmek verinizi silmez.

## Garuda/Arch paketini kurma, güncelleme ve kaldırma

```bash
sudo pacman -U pnp-tracker-<sürüm>-1-x86_64.pkg.tar.zst   # kurulum ve güncelleme
pnp-tracker                                                # komut satırından açma
sudo pacman -R pnp-tracker                                 # kaldırma
```

Uygulama `/opt/pnp-tracker` altına kurulur, başlatıcı `/usr/bin/pnp-tracker`
olur ve menüde **PnP Üretim Takipçisi** olarak görünür. Güncelleme, yeni sürümün
paketini aynı `pacman -U` komutuyla kurmaktır; veriniz olduğu yerde kalır.

## Paket kaldırılınca veriniz neden durur?

Paket yalnızca uygulamanın kendi dosyalarını (`/opt/pnp-tracker`, başlatıcı,
menü girdisi, simge) kurar ve kaldırırken yalnız onları siler. Verileriniz
uygulamanın kurulduğu yerde değil, sizin kullanıcı dizinlerinizde durur; bir
paket yöneticisi oraya dokunmaz. Böylece kaldırıp yeniden kurduğunuzda
kaldığınız yerden devam edersiniz.

Veriyi de silmek isterseniz bu **ayrı ve elle** yapılan bir iştir:

1. Önce uygulamadan **Yedek oluştur** ile yedek alın ve yedeği başka bir yere
   kopyalayın.
2. Silmeden önce yolun doğru olduğunu görün:

   ```bash
   ls -la "${XDG_DATA_HOME:-$HOME/.local/share}/pnp-tracker"
   ```

3. Listeyi gördükten ve doğru dizin olduğuna emin olduktan sonra dizini kendi
   dosya yöneticinizle silin. Ayar ve tanılama dizinleri de aynı biçimde
   kontrol edilerek silinebilir.

## Bilinen sınırlar

- Yalnız Linux, yalnız `x86_64` ve glibc. Windows ve macOS sürümü yoktur.
- Arayüz X11 kullanır; Wayland oturumunda XWayland gerekir.
- Tek kullanıcı, tek kopya: aynı veriyle iki uygulama penceresi aynı anda
  çalışamaz. İkinci kopya açılmaz ve bunu söyler.
- Senkronizasyon, bulut ve çoklu cihaz yoktur; veri taşımanın yolu yedektir.
- Uygulama arayüzü Türkçedir.

## Lisans

Uygulamanın kendi kaynak kodu MIT lisanslıdır; metin deponun kökündeki
[`LICENSE`](../LICENSE) dosyasındadır. Kurulu pakette aynı metin
`/usr/share/licenses/pnp-tracker/LICENSE` ve `/opt/pnp-tracker/LICENSE`
yollarında durur.

Uygulamayla birlikte gelen Java çalışma ortamı ve üçüncü taraf kütüphaneler
kendi lisanslarıyla dağıtılır; MIT lisansı onları kapsamaz. Hangi bileşenin
geldiği ve lisans metinlerinin nerede durduğu paketin içindeki
`THIRD_PARTY_NOTICES.md` dosyasında yazar.
