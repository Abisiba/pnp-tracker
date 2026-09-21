# Katkı yönergeleri

Bu depo tek bir uygulamayı taşır: yerel çalışan, çevrimdışı bir Linux masaüstü
uygulaması. Ürünün ne olduğu ve ne olmadığı `PLAN.md` dosyasında yazılıdır;
`PLAN.md` bağlayıcıdır, kod ona uyar.

## Geliştirme ortamı

- **JDK 21.** Derleme `jvmToolchain(21)` ister; başka bir sürümle çalışmaz.
- **Gradle wrapper.** Depodaki `./gradlew` kullanılır; makinedeki Gradle değil.
  `gradle/wrapper/gradle-wrapper.properties` ve `gradle-wrapper.jar` birlikte
  değişir, ayrı ayrı değil.
- Bağımlılıklar `gradle/libs.versions.toml` içindedir. Yeni bağımlılık eklemek
  ayrı bir karardır; PLAN'daki bağımlılık listesiyle çelişmemelidir.

## Testler

```bash
./gradlew clean check                 # ktlint + bütün testler
./gradlew :app:desktopTest --tests 'dev.pnptracker.…'   # tek sınıf
./gradlew :app:ktlintFormat           # biçim düzeltme
```

Belleği sınırlı makinede ve sürekli tümleştirmede koşan biçim:

```bash
./gradlew clean check --rerun-tasks --no-daemon --no-parallel --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
```

Pencere açan doğrulamalar `check`'in parçası değildir; ekran isterler:

```bash
./gradlew :app:desktopWindowSmoke
./gradlew :app:verifyLinuxPackage
./gradlew :app:verifyArchPackage
```

Açılan pencere yalnız `SafeWindowCloser` ile, doğrulanmış süreç kimliği ve tam
başlıkla kapatılır. Başlık parçasına bakarak pencere aramak yasaktır: başkasının
penceresini kapatabilir.

## Geçici XDG dizinleri — zorunlu

Uygulamayı veya bir testi elle çalıştırırken **her zaman** üç geçici dizin verin:

```bash
tmp=$(mktemp -d)
XDG_DATA_HOME="$tmp/data" XDG_CONFIG_HOME="$tmp/config" XDG_STATE_HOME="$tmp/state" \
  ./gradlew :app:run
```

Kuralın nedeni basit: uygulamanın verisi
`$XDG_DATA_HOME/pnp-tracker/pnp.db` dosyasındadır ve bir test koşusu oraya
yazarsa gerçek bir insanın verisini bozar.

- Hiçbir test, hiçbir script ve hiçbir örnek komut gerçek kullanıcının
  veritabanına, yedeklerine, ayarlarına veya tanılama kayıtlarına dokunmaz.
- Silme yapan bir script, sileceği yolun kendi ürettiği geçici kök altında
  olduğunu silmeden önce doğrular (`packaging/verify/lib.sh` buna örnektir).
- Test ve script'ler kendi süreçlerini ve kendi dosyalarını toplar; başkasının
  süreçlerini durdurmaz.

## Room şeması ve migration

- Şema dosyaları `app/schemas/` altında dışa aktarılır ve **commit edilir**.
  Şema sürümü artıyorsa yeni `N.json` dosyası da aynı commit'te olmalıdır.
- Şema değiştiren her adımın migration'ı ve migration testi olur; eski sürümden
  bugüne yürüyen zincir kırılmaz.
- Dışa aktarılmış eski şema dosyaları düzenlenmez. Bir test koşusu bu dosyaları
  değiştiriyorsa bu bir kusurdur; CI `git diff --exit-code` ile buna düşer.
- Yedek biçimi (`formatVersion`) ve şema sürümü ayrı şeylerdir; biri değişince
  diğeri kendiliğinden değişmez.

## Tek sürüm kaynağı

Sürüm yalnız `app/build.gradle.kts` içindeki `version` değeridir. Uygulamanın
gösterdiği sürüm, arşiv ve paket adları, Arch `pkgver` değeri ve yayın etiketi
hep oradan türetilir. Başka bir yere sürüm numarası yazmayın; belgelerde
`<sürüm>` yazın. Yayın etiketi yalnız `v<sürüm>` olabilir
(`./gradlew :app:checkReleaseTag -PreleaseTag=v<sürüm>`).

## Commit ve pull request

- Bir commit bir iş yapar ve tek başına derlenip geçer. Karışık commit
  bölünür.
- Commit başlığı yapılan işi söyler: `fix(restore): …`, `build(linux): …`,
  `docs: …`, `test(packaging): …`. Gövde **neden** yapıldığını anlatır.
- Geçmiş yeniden yazılmaz: `--amend`, `reset --hard`, `checkout --`, `restore`
  ve `clean` kullanılmaz; düzeltme yeni bir commit'tir.
- Gönderimden önce: `./gradlew clean check` yeşil, `git diff --check` temiz,
  `git status --short` boş.
- Pull request ne değiştiğini, neden değiştiğini ve nasıl doğrulandığını yazar;
  davranış değiştiyse onu ölçen testi gösterir.
- CI her pull request'te aynı `check` komutunu koşar ve yalnız okuma izniyle
  çalışır; secret istemez, bu yüzden fork'tan gelen pull request de doğrulanır.

## Depoya girmeyecek şeyler

- Gerçek bir Excel/CSV üretim dosyası, gerçek oyun listesi veya başka kişisel
  içerik. Örnek dosyalar anonimdir (`docs/ornek-ice-aktarma.csv`,
  `app/src/desktopTest/resources/sample-import.xlsx`).
- `/home/<ad>/…` gibi makineye özel yollar, kullanıcı adları, e-posta adresleri.
- Gerçek bir veritabanı, yedek dosyası, bunların parmak izleri veya tanılama
  kayıtları.
- Anahtar, parola, token ve benzeri secret'lar. Yayın akışının ayrı bir secret'ı
  yoktur; GitHub'ın kendi `GITHUB_TOKEN` değerini yalnız yayın işi kullanır.

## Paketleme doğrulaması

```bash
./gradlew :app:packageLinuxArchive    # taşınabilir tar.gz
./gradlew :app:verifyLinuxPackage     # arşivi depo dışında açar, denetler, çalıştırır
./gradlew :app:packageArch            # .pkg.tar.zst (makepkg gerekir)
./gradlew :app:verifyArchPackage      # paketi geçici köke açar, denetler, çalıştırır
./gradlew :app:packageRelease         # iki paket + SHA256SUMS + belgeler
```

Paket doğrulaması bu makineye hiçbir şey kurmaz: geçici bir köke açar ve oradan
çalıştırır. Temiz bir Garuda ortamında yapılacak gerçek kurulum turu ayrıdır ve
[`packaging/verify/README.md`](packaging/verify/README.md) dosyasında anlatılır;
o script'ler `sudo`yu yalnız `pacman` için kullanır ve başlamadan önce açık onay
ister.

## Lisans

Katkınızı gönderdiğinizde, katkınızın projenin lisansı altında — MIT,
[`LICENSE`](LICENSE) — dağıtılmasını kabul etmiş olursunuz.
