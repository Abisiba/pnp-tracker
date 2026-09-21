#!/usr/bin/env bash
# Shared pieces of the Garuda verification (Faz 3 / İş 13).
#
# Sourced by preflight.sh and verify-clean-install.sh. Holds the result matrix,
# the package name and metadata rules, and the one deletion guard — so the rules
# are written once and can be tested on their own.

set -euo pipefail

PNP_PACKAGE_NAME="pnp-tracker"
PNP_INSTALL_DIRECTORY="/opt/pnp-tracker"
PNP_LAUNCHER="/usr/bin/pnp-tracker"
PNP_DESKTOP_ENTRY="/usr/share/applications/pnp-tracker.desktop"
PNP_ICON="/usr/share/icons/hicolor/256x256/apps/pnp-tracker.png"

# What the application writes, under the XDG folders of whoever runs it.
PNP_DATA_SUBDIRECTORY="pnp-tracker"
PNP_DATABASE_FILE="pnp.db"

# Only a directory whose name starts with this is ever deleted by these scripts.
PNP_TEMPORARY_PREFIX="pnp-verify-"

# name | status | detail — filled by `record`, printed by `print_report`.
PNP_RESULTS=()
PNP_FAILURES=0

say() { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }

# record <name> <PASS|FAIL|SKIPPED|MANUAL|INFO> <detail…>
record() {
  local name=$1 status=$2
  shift 2
  PNP_RESULTS+=("$name|$status|$*")
  printf '   [%s] %s — %s\n' "$status" "$name" "$*"
  [[ $status == FAIL ]] && PNP_FAILURES=$((PNP_FAILURES + 1))
  return 0
}

# check <name> <detail> <command…> — runs the command, records PASS or FAIL.
check() {
  local name=$1 detail=$2
  shift 2
  if "$@"; then
    record "$name" PASS "$detail"
  else
    record "$name" FAIL "$detail (komut: $*)"
  fi
}

print_report() {
  printf '\n================ SONUÇ MATRİSİ ================\n'
  printf '%-44s %-8s %s\n' "ADIM" "DURUM" "AYRINTI"
  local line name status detail
  for line in "${PNP_RESULTS[@]}"; do
    name=${line%%|*}
    status=${line#*|}
    detail=${status#*|}
    status=${status%%|*}
    printf '%-44s %-8s %s\n' "$name" "$status" "$detail"
  done
  printf '===============================================\n'
  if ((PNP_FAILURES > 0)); then
    printf 'SONUÇ: %d adım BAŞARISIZ.\n' "$PNP_FAILURES"
    return 1
  fi
  printf 'SONUÇ: başarısız adım yok.\n'
  return 0
}

# The stage a failure stopped at, so nothing looks finished when it is not.
PNP_STAGE="başlangıç"
stage() {
  PNP_STAGE=$1
  step "$1"
}
report_stage_on_error() {
  local code=$?
  if ((code != 0)); then
    printf '\nDURDU: "%s" aşamasında, çıkış kodu %d. Daha ileri adım çalıştırılmadı.\n' "$PNP_STAGE" "$code" >&2
  fi
  return "$code"
}

# ------------------------------------------------------------------ package

# expected_package_file_name <version> <release> <arch>
expected_package_file_name() {
  printf '%s-%s-%s-%s.pkg.tar.zst' "$PNP_PACKAGE_NAME" "$1" "$2" "$3"
}

# version_from_package_file_name <file name> — "0.1.0-1" or empty.
version_from_package_file_name() {
  local name=${1##*/}
  [[ $name =~ ^pnp-tracker-([0-9]+\.[0-9]+\.[0-9]+)-([0-9]+)-([a-z0-9_]+)\.pkg\.tar\.zst$ ]] || return 1
  printf '%s-%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
}

# arch_from_package_file_name <file name>
arch_from_package_file_name() {
  local name=${1##*/}
  [[ $name =~ ^pnp-tracker-[0-9]+\.[0-9]+\.[0-9]+-[0-9]+-([a-z0-9_]+)\.pkg\.tar\.zst$ ]] || return 1
  printf '%s' "${BASH_REMATCH[1]}"
}

# pkginfo_value <package file> <key>
pkginfo_value() {
  bsdtar -xOf "$1" .PKGINFO | sed -n "s/^$2 = //p" | head -1
}

# sha256_of <file>
sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

# ------------------------------------------------------------------ safety

# The one place a directory is removed. It must be a directory this run made:
# under the system temporary directory and named with the prefix above.
remove_own_temporary_directory() {
  local target=${1:-}
  local resolved
  [[ -n $target ]] || { say "silinecek dizin verilmedi"; return 1; }
  [[ -d $target ]] || return 0
  resolved=$(cd "$target" && pwd -P)
  local temporary
  temporary=$(cd "${TMPDIR:-/tmp}" && pwd -P)
  if [[ $resolved != "$temporary/"* || ${resolved##*/} != "$PNP_TEMPORARY_PREFIX"* ]]; then
    say "GÜVENLİK: $resolved bu koşunun geçici dizini değil; silinmedi."
    return 1
  fi
  rm -rf -- "$resolved"
}

# A listing of a directory's entries with their sizes, to compare before/after.
directory_fingerprint() {
  local directory=$1
  [[ -d $directory ]] || { printf 'YOK\n'; return 0; }
  find "$directory" -mindepth 1 -maxdepth 1 -printf '%y %s %f\n' 2>/dev/null | LC_ALL=C sort
}

# Every file under a directory with its digest, for "nothing changed" checks.
tree_fingerprint() {
  local directory=$1
  [[ -d $directory ]] || { printf 'YOK\n'; return 0; }
  find "$directory" -type f -printf '%P\n' 2>/dev/null | LC_ALL=C sort | while read -r relative; do
    printf '%s %s\n' "$(sha256_of "$directory/$relative")" "$relative"
  done
}

# ------------------------------------------------------------------ waiting

# Runs the application as the ordinary user with folders of its own, and waits
# for the person to close its window. No window is ever closed by these scripts.
run_application_and_wait() {
  local launcher=$1 root=$2
  local path_without_java="$root/bos-path"
  mkdir -p "$path_without_java" "$root/veri" "$root/ayar" "$root/durum" "$root/calisma"
  say "Uygulama açılıyor: $launcher"
  say "Pencereyi kendiniz kapatın; bu script hiçbir pencereyi kapatmaz."
  (
    cd "$root/calisma" || exit 1
    env -u JAVA_HOME -u JDK_JAVA_OPTIONS -u JAVA_TOOL_OPTIONS \
      PATH="$path_without_java" \
      HOME="$root" \
      XDG_DATA_HOME="$root/veri" \
      XDG_CONFIG_HOME="$root/ayar" \
      XDG_STATE_HOME="$root/durum" \
      DISPLAY="${DISPLAY:-}" \
      WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-}" \
      XAUTHORITY="${XAUTHORITY:-}" \
      XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-}" \
      LANG="${LANG:-C.UTF-8}" \
      "$launcher"
  )
}

# ------------------------------------------------------------------ asking

# ask_yes_no <question> — the person answers e/h; returns 0 for evet.
ask_yes_no() {
  local answer
  read -r -p "$1 (e/h) " answer || answer=h
  [[ $answer == e || $answer == E ]]
}

# confirm_exactly <word> — refuses anything but the word typed in full.
confirm_exactly() {
  local expected=$1 answer
  read -r -p "Devam etmek için \"$expected\" yazın: " answer || answer=""
  [[ $answer == "$expected" ]]
}
