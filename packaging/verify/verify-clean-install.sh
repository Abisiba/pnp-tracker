#!/usr/bin/env bash
# Faz 3 / İş 13 — temiz Garuda ortamında kurulum, açılış, veri dizini, güncelleme
# ve kaldırma doğrulaması.
#
#   ./verify-clean-install.sh /yol/pnp-tracker-<sürüm>-1-x86_64.pkg.tar.zst \
#       [--eski-paket /yol/önceki-sürüm.pkg.tar.zst]
#
# Bu script TEMİZ BİR SANAL MAKİNE içindir. Yaptıkları açıkça sorulur ve onay
# alınmadan hiçbir şey kurulmaz. Yalnız `pacman` adımları yükseltilmiş yetkiyle
# çalışır; uygulama her zaman normal kullanıcı olarak, bu koşuya ait geçici XDG
# dizinleriyle açılır. Script hiçbir pencereyi kapatmaz: pencereyi siz kapatırsınız.
#
# Görsel adımlar otomatik kanıtlanamaz; her biri ekranda tek tek sorulur ve
# cevabınız sonuç matrisine yazılır. Dosya düzeyinde kanıtlanabilen her şey
# (kurulum yerleri, veritabanı, dışa aktarılan CSV, yedek dosyası, tanılama
# satırı, kaldırma sonrası durum) otomatik denetlenir.

set -euo pipefail
trap 'report_stage_on_error' EXIT

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=lib.sh
source "$HERE/lib.sh"

PACKAGE=${1:-}
OLD_PACKAGE=""
shift || true
while (($# > 0)); do
  case $1 in
    --eski-paket)
      OLD_PACKAGE=${2:-}
      shift 2
      ;;
    *)
      say "Bilinmeyen seçenek: $1"
      exit 2
      ;;
  esac
done

if [[ -z $PACKAGE ]]; then
  say "Kullanım: $0 <paket .pkg.tar.zst> [--eski-paket <önceki sürüm .pkg.tar.zst>]"
  exit 2
fi
if [[ $EUID -eq 0 ]]; then
  say "Bu script root olarak çalıştırılmaz. Normal kullanıcı olarak çalıştırın;"
  say "yalnız pacman adımları için yetki istenecek."
  exit 2
fi

# --------------------------------------------------------------- pacman yolu

# Bütün yükseltilmiş komutlar buradan geçer: başka hiçbir yerde sudo yoktur.
pacman_as_root() {
  say "-> yetki gerekiyor: sudo pacman $*"
  sudo pacman "$@"
}

# --------------------------------------------------------------- onay

stage "ne yapılacak"
cat <<'ACIKLAMA'
Bu doğrulama şunları yapacak:

  1. Ön kontrol (hiçbir şeyi değiştirmez).
  2. sudo pacman -U ile paketi KURAR.
  3. Kurulum yerlerini ve izinleri denetler.
  4. Uygulamayı normal kullanıcı olarak, geçici XDG dizinleriyle açar;
     pencereyi siz kapatırsınız. Gerçek verileriniz kullanılmaz.
  5. İçe aktarma, onay, dışa aktarma, yedek, geri yükleme ve tanılama
     adımlarını size tek tek yaptırır ve dosya düzeyinde denetler.
  6. Aynı sürümü yeniden kurar; istenirse eski sürümden yükseltmeyi dener.
  7. sudo pacman -R ile paketi KALDIRIR ve kullanıcı verisinin durduğunu denetler.
  8. Yeniden kurup verinin açıldığını doğrular.

Yapmayacakları: kullanıcı verisini silmez, gerçek ev dizininize yazmaz, pencere
kapatmaz, ağa çıkmaz, AUR veya başka bir depoya bir şey göndermez.
ACIKLAMA
say ""
if ! confirm_exactly "EVET"; then
  say "Onay verilmedi; hiçbir şey yapılmadı."
  exit 1
fi

# --------------------------------------------------------------- ön kontrol

stage "ön kontrol"
if bash "$HERE/preflight.sh" "$PACKAGE"; then
  record "ön kontrol" PASS "preflight.sh başarısız adım bulmadı"
else
  record "ön kontrol" FAIL "preflight.sh başarısız adım buldu; kuruluma geçilmedi"
  print_report
  exit 1
fi

# --------------------------------------------------------------- hazırlık

stage "geçici çalışma alanı"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/${PNP_TEMPORARY_PREFIX}XXXXXX")
record "geçici kök" INFO "$TEST_ROOT (bu koşu oluşturdu, sonunda silinecek)"
DATA="$TEST_ROOT/veri/$PNP_DATA_SUBDIRECTORY"
EXPORTS="$TEST_ROOT/disa-aktarim"
mkdir -p "$EXPORTS"

# Gerçek kullanıcının kendi alanları: hiçbir adım bunlara dokunmamalı.
REAL_HOME_BEFORE=$(directory_fingerprint "$HOME")
REAL_DATA_BEFORE=$(directory_fingerprint "${XDG_DATA_HOME:-$HOME/.local/share}/$PNP_DATA_SUBDIRECTORY")

# --------------------------------------------------------------- kurulum

stage "kurulum"
pacman_as_root -U --noconfirm "$PACKAGE"
record "pacman -U" PASS "paket kuruldu"

check "kurulu sürüm okunuyor" "pacman -Q" bash -c "pacman -Q $PNP_PACKAGE_NAME >/dev/null"
record "kurulu sürüm" INFO "$(pacman -Q "$PNP_PACKAGE_NAME")"

stage "kurulum yerleşimi"
for path in "$PNP_INSTALL_DIRECTORY" "$PNP_LAUNCHER" "$PNP_DESKTOP_ENTRY" "$PNP_ICON"; do
  if [[ -e $path ]]; then
    record "kuruldu: $path" PASS "$(stat -c '%A %U:%G' "$path")"
  else
    record "kuruldu: $path" FAIL "yok"
  fi
done
check "başlatıcı /opt'a çözülüyor" "$PNP_LAUNCHER -> $PNP_INSTALL_DIRECTORY/bin/pnp-tracker" \
  bash -c "[[ \$(readlink -f '$PNP_LAUNCHER') == '$PNP_INSTALL_DIRECTORY/bin/pnp-tracker' ]]"
check "/opt kullanıcı tarafından yazılamaz" "normal kullanıcı /opt/pnp-tracker'a yazamamalı" \
  bash -c "[[ ! -w '$PNP_INSTALL_DIRECTORY' ]]"
check "gömülü runtime kurulu" "lib/runtime/lib/server/libjvm.so" \
  test -f "$PNP_INSTALL_DIRECTORY/lib/runtime/lib/server/libjvm.so"
check "paket dosyaları bozulmamış" "pacman -Qkk" bash -c "pacman -Qkk $PNP_PACKAGE_NAME >/dev/null"
if command -v desktop-file-validate >/dev/null; then
  check "masaüstü girdisi geçerli" "desktop-file-validate" desktop-file-validate "$PNP_DESKTOP_ENTRY"
else
  record "masaüstü girdisi geçerli" SKIPPED "desktop-file-validate kurulu değil"
fi
record "masaüstü girdisi Exec/Icon" \
  "$(grep -qx 'Exec=pnp-tracker' "$PNP_DESKTOP_ENTRY" && grep -qx 'Icon=pnp-tracker' "$PNP_DESKTOP_ENTRY" && echo PASS || echo FAIL)" \
  "$(grep -E '^(Exec|Icon)=' "$PNP_DESKTOP_ENTRY" | tr '\n' ' ')"

# --------------------------------------------------------------- ilk açılış

stage "ilk açılış (gömülü runtime)"
say "Uygulama, PATH'inde java OLMADAN ve JAVA_HOME tanımsızken açılacak."
run_application_and_wait "$PNP_LAUNCHER" "$TEST_ROOT" && FIRST_EXIT=0 || FIRST_EXIT=$?
record "ilk açılış çıkış kodu" "$([[ $FIRST_EXIT -eq 0 ]] && echo PASS || echo FAIL)" "çıkış $FIRST_EXIT"
check "veritabanı oluştu" "$DATA/$PNP_DATABASE_FILE" test -f "$DATA/$PNP_DATABASE_FILE"
if compgen -G "$DATA/$PNP_DATABASE_FILE-wal" >/dev/null || compgen -G "$DATA/$PNP_DATABASE_FILE-shm" >/dev/null; then
  record "temiz kapanış" FAIL "-wal/-shm dosyası kaldı"
else
  record "temiz kapanış" PASS "-wal/-shm kalmadı"
fi
if [[ "$(directory_fingerprint "${XDG_DATA_HOME:-$HOME/.local/share}/$PNP_DATA_SUBDIRECTORY")" == "$REAL_DATA_BEFORE" ]]; then
  record "gerçek veri dizini oluşmadı" PASS "uygulama yalnız geçici XDG dizinlerine yazdı"
else
  record "gerçek veri dizini oluşmadı" FAIL "kullanıcının kendi pnp-tracker dizini değişti"
fi
ask_yes_no "Pencere açıldı ve uygulama adı \"PnP Üretim Takipçisi\" olarak göründü mü?" &&
  record "pencere göründü" MANUAL "evet" || record "pencere göründü" FAIL "hayır"

# --------------------------------------------------------------- kullanım turu

stage "kullanım turu (geçici verilerle)"
say "Uygulama yeniden açılacak. Sırayla şunları yapın, sonra pencereyi kapatın:"
cat <<'ADIMLAR'
  1. Oyunlar ekranında bir oyun oluşturun.
  2. İçe Aktarma ekranından örnek XLSX dosyasını seçip taslak olarak kaydedin.
  3. Taslağı inceleyin, bir görevi düzenleyin ve onaylayın.
  4. Ayarlar ekranından "Görevleri CSV'ye aktar" ile aşağıdaki klasöre yazın.
  5. Ayarlar ekranından "Yedek oluştur" ile aynı klasöre bir yedek alın.
  6. Aynı yedeği "Yedekten geri yükle" ile geri yükleyin.
  7. "Yedekten geri yükle" ile bir METİN dosyası seçin; reddedilmeli.
ADIMLAR
say "Dışa aktarma ve yedek klasörü: $EXPORTS"
run_application_and_wait "$PNP_LAUNCHER" "$TEST_ROOT" && TOUR_EXIT=0 || TOUR_EXIT=$?
record "kullanım turu çıkış kodu" "$([[ $TOUR_EXIT -eq 0 ]] && echo PASS || echo FAIL)" "çıkış $TOUR_EXIT"

CSV_FILE=$(find "$EXPORTS" -maxdepth 1 -name '*.csv' -type f | head -1 || true)
if [[ -n $CSV_FILE ]]; then
  if head -c 512 "$CSV_FILE" | tr -d '\r' | head -1 | grep -qx $'\xef\xbb\xbfgame,column,task,pool,colors,required_quantity,status,notes'; then
    record "CSV dışa aktarma" PASS "$(basename -- "$CSV_FILE") — başlık ve BOM sözleşmeye uygun"
  else
    record "CSV dışa aktarma" FAIL "$(basename -- "$CSV_FILE") başlığı beklenen sütunlarla başlamıyor"
  fi
else
  record "CSV dışa aktarma" FAIL "klasörde .csv dosyası yok"
fi

BACKUP_FILE=$(find "$EXPORTS" "$DATA/backups" -maxdepth 1 -name 'pnp-yedek-*.json' -type f 2>/dev/null | head -1 || true)
if [[ -n $BACKUP_FILE ]] && grep -q '"pnp-tracker-backup"' "$BACKUP_FILE"; then
  record "manuel yedek" PASS "$(basename -- "$BACKUP_FILE")"
else
  record "manuel yedek" FAIL "pnp-yedek-*.json bulunamadı ya da içeriği yedek biçiminde değil"
fi

if compgen -G "$DATA/backups/pnp-oncesi-*.json" >/dev/null; then
  record "geri yükleme güvenlik yedeği" PASS "$(basename -- "$(find "$DATA/backups" -name 'pnp-oncesi-*.json' | head -1)")"
else
  record "geri yükleme güvenlik yedeği" FAIL "geri yüklemeden önce pnp-oncesi-*.json oluşmamış"
fi

LOG_DIRECTORY="$TEST_ROOT/durum/$PNP_DATA_SUBDIRECTORY/logs"
LOG_LINES=$(cat "$LOG_DIRECTORY"/*.jsonl 2>/dev/null | wc -l) || LOG_LINES=0
if ((LOG_LINES > 0)); then
  if grep -q "$USER" "$LOG_DIRECTORY"/*.jsonl 2>/dev/null || grep -q "$HOME" "$LOG_DIRECTORY"/*.jsonl 2>/dev/null; then
    record "tanılama kaydı" FAIL "kayıtta kullanıcı adı ya da ev dizini yolu geçiyor"
  else
    record "tanılama kaydı" PASS "$LOG_LINES satır; kullanıcı adı ve yol yok"
  fi
else
  record "tanılama kaydı" FAIL "reddedilen geri yükleme bir satır yazmalıydı ($LOG_DIRECTORY boş)"
fi

ask_yes_no "İçe aktarma taslağı, onay ve geri yükleme ekranları Türkçe ve anlaşılır mıydı?" &&
  record "ekran metinleri" MANUAL "evet" || record "ekran metinleri" FAIL "hayır"

stage "kapanış ve yeniden açılış"
say "Uygulama son bir kez açılacak: verilerinizin durduğunu görün ve kapatın."
run_application_and_wait "$PNP_LAUNCHER" "$TEST_ROOT" && REOPEN_EXIT=0 || REOPEN_EXIT=$?
record "yeniden açılış çıkış kodu" "$([[ $REOPEN_EXIT -eq 0 ]] && echo PASS || echo FAIL)" "çıkış $REOPEN_EXIT"
ask_yes_no "Önceki turda oluşturduğunuz oyun ve görevler yerinde miydi?" &&
  record "veri yeniden açılışta duruyor" MANUAL "evet" || record "veri yeniden açılışta duruyor" FAIL "hayır"

DATA_AFTER_TOUR=$(tree_fingerprint "$DATA")

# --------------------------------------------------------------- yeniden kurulum

stage "aynı sürümü yeniden kurma"
pacman_as_root -U --noconfirm "$PACKAGE"
check "yeniden kurulumdan sonra dosyalar bozulmamış" "pacman -Qkk" bash -c "pacman -Qkk $PNP_PACKAGE_NAME >/dev/null"
if [[ "$(tree_fingerprint "$DATA")" == "$DATA_AFTER_TOUR" ]]; then
  record "yeniden kurulum kullanıcı verisine dokunmadı" PASS "veri dizini bayt bayt aynı"
else
  record "yeniden kurulum kullanıcı verisine dokunmadı" FAIL "veri dizini değişti"
fi

stage "güncelleme"
if [[ -n $OLD_PACKAGE ]]; then
  if [[ -f $OLD_PACKAGE ]]; then
    pacman_as_root -U --noconfirm "$OLD_PACKAGE"
    record "eski sürüm kuruldu" INFO "$(pacman -Q "$PNP_PACKAGE_NAME")"
    pacman_as_root -U --noconfirm "$PACKAGE"
    record "yükseltme" PASS "$(pacman -Q "$PNP_PACKAGE_NAME")"
    if [[ "$(tree_fingerprint "$DATA")" == "$DATA_AFTER_TOUR" ]]; then
      record "güncelleme kullanıcı verisine dokunmadı" PASS "veri dizini bayt bayt aynı"
    else
      record "güncelleme kullanıcı verisine dokunmadı" FAIL "veri dizini değişti"
    fi
  else
    record "güncelleme" FAIL "--eski-paket verildi ama dosya yok: $OLD_PACKAGE"
  fi
else
  record "güncelleme" SKIPPED "ölçülemedi: bu ilk sürüm; karşılaştırılacak daha eski bir paket yok (--eski-paket ile verilebilir)"
fi

# --------------------------------------------------------------- kaldırma

stage "kaldırma"
pacman_as_root -R --noconfirm "$PNP_PACKAGE_NAME"
for path in "$PNP_INSTALL_DIRECTORY" "$PNP_LAUNCHER" "$PNP_DESKTOP_ENTRY" "$PNP_ICON"; do
  if [[ -e $path ]]; then
    record "kaldırıldı: $path" FAIL "hâlâ duruyor"
  else
    record "kaldırıldı: $path" PASS "silindi"
  fi
done
if [[ "$(tree_fingerprint "$DATA")" == "$DATA_AFTER_TOUR" ]]; then
  record "kaldırma kullanıcı verisini silmedi" PASS "veri dizini bayt bayt aynı"
else
  record "kaldırma kullanıcı verisini silmedi" FAIL "veri dizini değişti"
fi

stage "kaldırmadan sonra yeniden kurulum"
pacman_as_root -U --noconfirm "$PACKAGE"
run_application_and_wait "$PNP_LAUNCHER" "$TEST_ROOT" && AGAIN_EXIT=0 || AGAIN_EXIT=$?
record "yeniden kurulumdan sonra açılış" "$([[ $AGAIN_EXIT -eq 0 ]] && echo PASS || echo FAIL)" "çıkış $AGAIN_EXIT"
ask_yes_no "Korunmuş verileriniz (oyunlar, görevler) yine yerinde miydi?" &&
  record "korunmuş veri açıldı" MANUAL "evet" || record "korunmuş veri açıldı" FAIL "hayır"

# --------------------------------------------------------------- gerçek kullanıcı alanı

stage "gerçek kullanıcının dosyaları"
if [[ "$(directory_fingerprint "$HOME")" == "$REAL_HOME_BEFORE" ]]; then
  record "ev dizini değişmedi" PASS "üst düzey girdiler aynı"
else
  record "ev dizini değişmedi" FAIL "ev dizininin üst düzey girdileri değişti"
fi
if [[ "$(directory_fingerprint "${XDG_DATA_HOME:-$HOME/.local/share}/$PNP_DATA_SUBDIRECTORY")" == "$REAL_DATA_BEFORE" ]]; then
  record "gerçek PNP veri dizini değişmedi" PASS "bu doğrulama yalnız geçici dizinleri kullandı"
else
  record "gerçek PNP veri dizini değişmedi" FAIL "gerçek veri dizini değişti (menüden açılan kopya bunu yapmış olabilir)"
fi

# --------------------------------------------------------------- menü

# En sonda, çünkü menüden açılan kopya geçici dizinleri değil, bu sanal
# makinedeki kendi XDG dizinlerinizi kullanır: yukarıdaki "gerçek veri dizini"
# denetimleri bu adımdan önce yapılmıştır.
stage "başlatma menüsü"
say "Masaüstü menüsünde (Uygulamalar) \"PnP Üretim Takipçisi\" girdisini bulun ve açın."
say "Bu adım bu makinedeki kendi veri dizininizi oluşturur; beklenen davranış budur."
if ask_yes_no "Menüden açıldı, simgesi göründü ve pencere kapanabildi mi?"; then
  record "menüden açılış" MANUAL "evet (kendi XDG dizinlerinizle)"
else
  record "menüden açılış" FAIL "hayır"
fi

# --------------------------------------------------------------- temizlik

stage "temizlik"
if pgrep -f "$PNP_INSTALL_DIRECTORY/bin/pnp-tracker" >/dev/null 2>&1; then
  record "arkada uygulama süreci yok" FAIL "hâlâ çalışan bir kopya var; kendiniz kapatın"
else
  record "arkada uygulama süreci yok" PASS "çalışan kopya yok"
fi
say "Bu koşunun geçici dizini siliniyor: $TEST_ROOT"
if remove_own_temporary_directory "$TEST_ROOT"; then
  record "geçici dizin silindi" PASS "$TEST_ROOT"
else
  record "geçici dizin silindi" FAIL "silinemedi: $TEST_ROOT"
fi

say ""
say "Doğrulama bitti. Paket bu makinede KURULU kaldı; silmek isterseniz:"
say "  sudo pacman -R pnp-tracker"

cat <<'SONRASI'

İSTEĞE BAĞLI — KULLANICI VERİSİNİ TEMİZLEME (bu script YAPMAZ)
--------------------------------------------------------------
Paketi kaldırmak verinizi silmez; bu bilinçli bir karardır. Veriyi de silmek
isterseniz, sırayla:

  1. Önce uygulamadan "Yedek oluştur" ile bir yedek alın ve yedeği başka bir
     diske kopyalayın.
  2. Silmeden önce yolun doğru olduğunu görün:
         ls -la "${XDG_DATA_HOME:-$HOME/.local/share}/pnp-tracker"
  3. Ancak listeyi gördükten ve doğru dizin olduğuna emin olduktan sonra o
     dizini kendi dosya yöneticinizle silin.

Ayar ve tanılama dizinleri de aynı biçimde kontrol edilerek silinebilir:
  "${XDG_CONFIG_HOME:-$HOME/.config}/pnp-tracker"
  "${XDG_STATE_HOME:-$HOME/.local/state}/pnp-tracker"
SONRASI

print_report
