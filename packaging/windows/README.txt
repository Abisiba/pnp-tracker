PnP Üretim Takipçisi — Windows uygulama dizini
==============================================

Bu dizin, sistemde Java kurulu olmasını gerektirmeyen, kendi başına çalışan
Windows uygulamasıdır. Kurucu onu buraya kopyalar; buradaki hiçbir dosyayı elle
değiştirmeniz gerekmez.

Uygulamayı Başlat menüsünden ya da bu dizindeki pnp-tracker.exe ile açarsınız.

İçerik:

    pnp-tracker.exe      uygulama başlatıcısı
    app\                 uygulamanın ve kütüphanelerinin jar dosyaları,
                         Compose/Skia için gerekli native kütüphane
    runtime\             uygulamayla gelen, yalnız gereken modülleri içeren
                         Java çalışma ortamı
    VERSION              paket adı, sürümü ve mimarisi
    LICENSE              uygulamanın lisansı (MIT)
    THIRD_PARTY_NOTICES.md  gömülü çalışma ortamının ve kütüphanelerin lisansları

Uygulama bu dizine hiçbir şey yazmaz. Verileriniz kendi kullanıcı
klasörlerinizde durur:

    %LOCALAPPDATA%\pnp-tracker\data\      veritabanı ve yedekler
    %LOCALAPPDATA%\pnp-tracker\state\     tanılama kayıtları ve tablo ölçüleri
    %APPDATA%\pnp-tracker\                ayarlar

Uygulamayı kaldırmak bu dizini siler; yukarıdaki üç klasöre dokunmaz. Verinizi
de silmek istiyorsanız, önce Ayarlar ekranından bir yedek alın, sonra o
klasörleri kendiniz kaldırın.

Kurucu imzasızdır: kod imzalama sertifikası yoktur. Windows SmartScreen bu
yüzden bir uyarı gösterebilir. İndirdiğiniz dosyanın doğru dosya olduğunu,
yayın sayfasındaki SHA256SUMS ile karşılaştırarak doğrulayabilirsiniz.
