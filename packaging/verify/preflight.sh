#!/usr/bin/env bash
# Faz 3 / İş 13 — ön kontrol. Hiçbir şey kurmaz, kaldırmaz veya değiştirmez.
#
#   ./preflight.sh /yol/pnp-tracker-<sürüm>-1-x86_64.pkg.tar.zst
#
# Ortamın gerçekten Garuda/Arch olduğunu, paketin daha önce kurulmadığını ve
# paket dosyasının adını, sürümünü, SHA-256 değerini ve metadata'sını kaydeder.
# `sudo` istemez; yalnız okuma yapar.

set -euo pipefail
trap 'report_stage_on_error' EXIT

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=lib.sh
source "$HERE/lib.sh"

PACKAGE=${1:-}
if [[ -z $PACKAGE ]]; then
  say "Kullanım: $0 <paket dosyası .pkg.tar.zst>"
  exit 2
fi

stage "ortam"

check "işletim sistemi Garuda/Arch" "/etc/os-release içinde arch" \
  bash -c '[[ -r /etc/os-release ]] && . /etc/os-release && [[ ${ID:-} == arch || ${ID:-} == garuda || ${ID_LIKE:-} == *arch* ]]'
record "dağıtım adı" INFO "$( ( [[ -r /etc/os-release ]] && . /etc/os-release && printf '%s %s' "${NAME:-bilinmiyor}" "${BUILD_ID:-}" ) || printf 'okunamadı')"

check "mimari x86_64" "uname -m" bash -c '[[ $(uname -m) == x86_64 ]]'
check "C kütüphanesi glibc" "ldd --version" bash -c 'ldd --version 2>/dev/null | head -1 | grep -qi "glibc\|GNU libc"'
record "glibc sürümü" INFO "$(ldd --version 2>/dev/null | head -1 || printf 'okunamadı')"
record "çekirdek" INFO "$(uname -sr)"

if [[ -n ${DISPLAY:-} || -n ${WAYLAND_DISPLAY:-} ]]; then
  record "grafik oturumu" PASS "oturum türü=${XDG_SESSION_TYPE:-bilinmiyor} DISPLAY=${DISPLAY:-yok} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-yok}"
else
  record "grafik oturumu" FAIL "ne DISPLAY ne WAYLAND_DISPLAY var; uygulama penceresi açılamaz"
fi
if [[ ${XDG_SESSION_TYPE:-} == wayland ]]; then
  record "X11/XWayland" INFO "Wayland oturumu: uygulama AWT/X11 kullanır, XWayland gerekir"
fi

stage "gerekli araçlar"
for tool in pacman bsdtar sha256sum find; do
  check "araç: $tool" "PATH üzerinde" command -v "$tool" >/dev/null
done
for tool in desktop-file-validate namcap; do
  if command -v "$tool" >/dev/null; then
    record "araç: $tool" INFO "var"
  else
    record "araç: $tool" SKIPPED "kurulu değil; bu aracın denetimi atlanacak"
  fi
done
if command -v java >/dev/null; then
  record "sistemde java" INFO "var ($(command -v java)); uygulama yine de kendi runtime'ını kullanmalı"
else
  record "sistemde java" INFO "yok; uygulama yalnız paketin içindeki runtime ile çalışacak"
fi

stage "paket kurulu değil"
if pacman -Qq "$PNP_PACKAGE_NAME" >/dev/null 2>&1; then
  record "paket kurulu değil" FAIL "$PNP_PACKAGE_NAME zaten kurulu: $(pacman -Q "$PNP_PACKAGE_NAME" 2>/dev/null)"
else
  record "paket kurulu değil" PASS "pacman -Qq $PNP_PACKAGE_NAME sonuç vermiyor"
fi
for path in "$PNP_INSTALL_DIRECTORY" "$PNP_LAUNCHER" "$PNP_DESKTOP_ENTRY" "$PNP_ICON"; do
  if [[ -e $path ]]; then
    record "kurulum yeri boş: $path" FAIL "zaten var"
  else
    record "kurulum yeri boş: $path" PASS "yok"
  fi
done

stage "paket dosyası"
if [[ ! -f $PACKAGE ]]; then
  record "paket dosyası" FAIL "$PACKAGE bulunamadı"
  print_report
  exit 1
fi
record "paket dosyası" INFO "$(basename -- "$PACKAGE"), $(stat -c %s "$PACKAGE") bayt"

PACKAGE_VERSION=""
if PACKAGE_VERSION=$(version_from_package_file_name "$PACKAGE"); then
  record "dosya adı biçimi" PASS "sürüm $PACKAGE_VERSION, mimari $(arch_from_package_file_name "$PACKAGE")"
else
  record "dosya adı biçimi" FAIL "ad pnp-tracker-<sürüm>-<yayın>-<mimari>.pkg.tar.zst kalıbına uymuyor"
fi

DIGEST=$(sha256_of "$PACKAGE")
record "paket SHA-256" INFO "$DIGEST"
if [[ -f "$PACKAGE.sha256" ]]; then
  if (cd -- "$(dirname -- "$PACKAGE")" && sha256sum -c --status "$(basename -- "$PACKAGE").sha256"); then
    record "SHA-256 dosyayla doğrulandı" PASS "$(basename -- "$PACKAGE").sha256"
  else
    record "SHA-256 dosyayla doğrulandı" FAIL "$(basename -- "$PACKAGE").sha256 ile uyuşmuyor"
  fi
else
  record "SHA-256 dosyayla doğrulandı" SKIPPED "yanında .sha256 dosyası yok; yukarıdaki değeri üretimdekiyle elle karşılaştırın"
fi

stage "paket metadata"
PKGINFO=$(bsdtar -xOf "$PACKAGE" .PKGINFO 2>/dev/null || true)
if [[ -z $PKGINFO ]]; then
  record "paket okunabildi" FAIL "bsdtar .PKGINFO okuyamadı; dosya bir Arch paketi değil ya da bozuk"
else
  record "paket okunabildi" PASS ".PKGINFO okundu"
  PKGNAME=$(sed -n 's/^pkgname = //p' <<<"$PKGINFO" | head -1)
  PKGVER=$(sed -n 's/^pkgver = //p' <<<"$PKGINFO" | head -1)
  PKGARCH=$(sed -n 's/^arch = //p' <<<"$PKGINFO" | head -1)
  DEPENDS=$(sed -n 's/^depend = //p' <<<"$PKGINFO" | tr '\n' ' ')
  if [[ $PKGNAME == "$PNP_PACKAGE_NAME" ]]; then
    record "pkgname" PASS "$PKGNAME"
  else
    record "pkgname" FAIL "$PKGNAME (beklenen $PNP_PACKAGE_NAME)"
  fi
  if [[ -n $PACKAGE_VERSION && $PKGVER == "$PACKAGE_VERSION" ]]; then
    record "pkgver dosya adıyla aynı" PASS "pkgver=$PKGVER"
  else
    record "pkgver dosya adıyla aynı" FAIL "pkgver=$PKGVER, dosya adı=$PACKAGE_VERSION"
  fi
  if [[ -n $PKGARCH && $PKGARCH == "$(arch_from_package_file_name "$PACKAGE" || true)" ]]; then
    record "pkgarch" PASS "$PKGARCH"
  else
    record "pkgarch" FAIL "$PKGARCH"
  fi
  record "bağımlılıklar" INFO "${DEPENDS:-yok}"
  if grep -qiE '(^| )(jre|jdk|java)[^ ]*' <<<" $DEPENDS "; then
    record "sistem Java bağımlılığı yok" FAIL "paket bir Java paketine bağımlı: $DEPENDS"
  else
    record "sistem Java bağımlılığı yok" PASS "depends listesinde java/jre/jdk yok"
  fi
fi

stage "paket içeriği"
LISTING=$(bsdtar -tf "$PACKAGE" 2>/dev/null || true)
if [[ -z $LISTING ]]; then
  record "paket içeriği okunabildi" FAIL "bsdtar dosyayı açamadı; içerik denetimleri yapılamadı"
else
  record "paket içeriği okunabildi" PASS "$(wc -l <<<"$LISTING") girdi"
  for entry in "opt/pnp-tracker/bin/pnp-tracker" "usr/bin/pnp-tracker" \
    "usr/share/applications/pnp-tracker.desktop" "usr/share/icons/hicolor/256x256/apps/pnp-tracker.png" \
    "usr/share/licenses/pnp-tracker/LICENSE" "opt/pnp-tracker/lib/runtime/release"; do
    if grep -qx "$entry" <<<"$LISTING"; then
      record "pakette: $entry" PASS "var"
    else
      record "pakette: $entry" FAIL "yok"
    fi
  done
  OUTSIDE=$(grep -vE '^(\.PKGINFO|\.BUILDINFO|\.MTREE|opt/|usr/)' <<<"$LISTING" | tr '\n' ' ' || true)
  if [[ -n ${OUTSIDE// /} ]]; then
    record "yalnız /opt ve /usr" FAIL "$OUTSIDE"
  else
    record "yalnız /opt ve /usr" PASS "başka yere kurulum yok"
  fi
  RUNTIME=$(bsdtar -xOf "$PACKAGE" opt/pnp-tracker/lib/runtime/release 2>/dev/null | sed -n 's/^JAVA_VERSION=//p' | tr -d '"' || true)
  record "gömülü runtime" "$([[ -n $RUNTIME ]] && echo PASS || echo FAIL)" "${RUNTIME:-runtime sürümü okunamadı}"
fi

print_report
