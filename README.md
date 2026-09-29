# PnP Üretim Takipçisi (PnP Production Tracker)

A desktop application that keeps track of 3D printing, card, board and special
production work for print-and-play (PnP) board games. It runs entirely on your
own computer and works offline.

The application's interface is in Turkish. The product scope, data model and
development phases are in `PLAN.md` (also in Turkish).

## What does it do?

It follows the work a PnP game needs — what to print, what to laminate, what to
glue onto board — game by game and cell by cell: it imports the Excel or CSV
list you already have, turns raw text into tasks only with your confirmation,
records progress and missing parts, and exports the result to CSV. Your data
stays on your computer; the application never goes online and asks for no
account.

## Supported platforms

- **Linux**: `x86_64`, glibc. The interface uses X11 (a Wayland session needs
  XWayland). Built and checked in CI, and used day to day on Garuda Linux.
- **Windows**: an `x86_64` installer is built and checked automatically on
  GitHub's Windows runner for every release. It **has not yet been tried by hand
  on a real Windows 11 installation**; treat it as a preview until that has been
  done.
- **macOS**: there is no macOS version.

## Downloads

Every release on the [Releases](https://github.com/Abisiba/pnp-tracker/releases) page carries:

| Kind | File | For |
| --- | --- | --- |
| Windows installer | `pnp-tracker-<version>-windows-x86_64.exe` | 64-bit Windows; run it to install (not yet tried on a real Windows 11 machine) |
| Portable archive | `pnp-tracker-<version>-linux-x86_64.tar.gz` | any Linux distribution; unpack and run |
| Arch package | `pnp-tracker-<version>-1-x86_64.pkg.tar.zst` | Garuda and Arch; `pacman -U` |
| Checksums | `SHA256SUMS` | verifying every file above |

None of them needs Java to be installed: the Java runtime is inside each
package. The *Source code (zip)* and *Source code (tar.gz)* links GitHub adds to
every release are the repository's source code, not an installer.

The packages are **not signed**. Verify a download against `SHA256SUMS` from the
same release:

```bash
sha256sum -c SHA256SUMS --ignore-missing
```

On Windows, compare the output of this PowerShell command with the `.exe` line
in `SHA256SUMS`:

```powershell
Get-FileHash -Algorithm SHA256 .\pnp-tracker-<version>-windows-x86_64.exe
```

Because the installer is unsigned, Windows SmartScreen may warn that it comes
from an unknown publisher.

## Documentation

- [User guide](docs/kullanim-kilavuzu.md) — for someone using the application
  for the first time, including installing on Linux, Arch and Windows.
- [Import guide](docs/ornek-ice-aktarma.md) — the XLSX and CSV formats, an
  example file and examples of rejected rows.
- [Clean Garuda verification](packaging/verify/README.md) — installing,
  updating and removing the package on a clean virtual machine (in Turkish).
- [Contributing](CONTRIBUTING.md) — development setup, test commands, the
  temporary XDG rule and commit expectations (in Turkish).

## Requirements for building

- JDK 21

## Checking from source

```bash
./gradlew run
./gradlew clean check
```

`check` runs ktlint and every test. The form that runs on a machine with little
memory, and in continuous integration:

```bash
./gradlew clean check --rerun-tasks --no-daemon --no-parallel --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
```

## Linux package

The version lives in one place, the `version` value in `app/build.gradle.kts`;
the package, the archive and the application's version all come from it.

```bash
./gradlew :app:packageLinuxArchive   # app/build/linux/dist/pnp-tracker-<version>-linux-<arch>.tar.gz
./gradlew :app:verifyLinuxPackage    # checks the archive and runs it from outside the repository (needs a display)
```

The archive is a self-contained application folder that needs no Java
installation (`pnp-tracker-<version>/bin/pnp-tracker`); it carries a Java
runtime with only the modules it needs.

## Garuda/Arch package

```bash
./gradlew :app:packageArch           # app/build/arch/dist/pnp-tracker-<version>-1-<arch>.pkg.tar.zst
./gradlew :app:verifyArchPackage     # unpacks the package into a temporary root, checks it and runs it (needs a display)
sudo pacman -U app/build/arch/dist/pnp-tracker-<version>-1-<arch>.pkg.tar.zst
```

The package hands the archive above to `makepkg` through
`packaging/arch/PKGBUILD`; the application is not built again. It is installed
under `/opt/pnp-tracker`, with the launcher at `/usr/bin/pnp-tracker` and the
desktop entry and icon in the freedesktop locations. No Java is needed on the
system; the data still lives in the user's XDG folders.

## Windows installer

The installer can only be built on Windows:

```powershell
.\gradlew.bat :app:packageWindows         # app\build\windows\dist\pnp-tracker-<version>-windows-x86_64.exe
.\gradlew.bat :app:verifyWindowsPackage   # builds it and checks what it carries
```

It installs per user, lets you choose the folder, and adds a Start menu entry
and a desktop shortcut. Data lives under `%LOCALAPPDATA%\pnp-tracker` and
`%APPDATA%\pnp-tracker`, never in the install folder. The installer is not
byte-for-byte reproducible between builds, so compare it only with the checksum
published beside it.

## Continuous integration and releases

Every pull request and every push to `main` runs the memory-limited `check`
above through `.github/workflows/ci.yml`, on Linux and on Windows; permissions
are read-only and the tests use temporary data folders. The Windows job also
builds the installer and keeps it as a workflow artifact.

A tag of the form `v<version>` runs `.github/workflows/release.yml`: first every
test, then the Linux archive and the Arch package, then the Windows installer on
GitHub's Windows runner, then one `SHA256SUMS` covering all of them, and finally
the GitHub release. The packages are published unsigned; they are verified with
`sha256sum -c SHA256SUMS`.

## Status

The application is usable: the data model, import, task tracking,
backup/restore and packaging are complete. Still to do: a hand-run installation
round on a real Windows 11 machine, and the clean Garuda installation round in
`packaging/verify/`. The next items are in section `18.` of `PLAN.md`.

## Licence

The application's own source code is under the MIT licence —
[`LICENSE`](LICENSE). The Java runtime and the third-party libraries shipped
with the packages are distributed under their own licences; which components
are included is written in the `THIRD_PARTY_NOTICES.md` file inside each
package.
