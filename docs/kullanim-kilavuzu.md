# PnP Üretim Takipçisi — User Guide

This guide is for someone using the application for the first time. It explains
what each screen is for and how everyday work is done.

The application's interface is in Turkish. This guide names every screen and
button by its Turkish label, exactly as it appears on screen, with an English
explanation beside it.

## What is PnP Tracker?

A desktop application, running entirely on your own computer, that keeps track
of the work involved in making print-and-play (PnP) board games yourself. It
shows what is left to do in each game for 3D printing, card lamination, board
cutting and special parts.

- It never goes online, asks for no account and sends no data anywhere.
- All data stays on your computer, in your own user folders.
- You can import the Excel or CSV list you already have, or build your own list
  from scratch.

## First start and where the data lives

The application creates its data file itself the first time it starts. It asks
nothing.

On Linux:

| What | Where |
| --- | --- |
| Database and backups | `$XDG_DATA_HOME/pnp-tracker/` (if unset, `~/.local/share/pnp-tracker/`) |
| Settings | `$XDG_CONFIG_HOME/pnp-tracker/settings.json` (if unset, `~/.config/pnp-tracker/`) |
| Diagnostic logs | `$XDG_STATE_HOME/pnp-tracker/logs/` (if unset, `~/.local/state/pnp-tracker/logs/`) |

On Windows:

| What | Where |
| --- | --- |
| Database and backups | `%LOCALAPPDATA%\pnp-tracker\data\` |
| Settings | `%APPDATA%\pnp-tracker\` |
| Diagnostic logs and table layout | `%LOCALAPPDATA%\pnp-tracker\state\` |

Three things are enough to know:

- Uninstalling the application does not delete these folders; your data stays.
- Nothing is ever written to the folder the application is installed in (the
  portable archive, `/opt/pnp-tracker`, or the Windows install folder).
- To move your data, take a backup in the application and restore it on the new
  machine (see below).

## Screens

The application opens on **Oyunlar** (Games). The single navigation row across
the top has five sections: **Oyunlar** (Games), **3D Baskı** (3D printing),
**Kartlar** (Cards), **Mukavva** (Board) and **Ayarlar** (Settings).

**Ayarlar** opens a menu: **Ayarlar** (Settings), **İçe/Dışa Aktarma**
(Import/Export), **Renkler** (Colours) and **Geçmiş** (History).

The **Özel** (Special) pool is not in the navigation: once you have a special
task, an **Özel görevler** entry appears on the game table and says how many
tasks are open.

## Creating a game

1. Go to **Oyunlar**.
2. Press **Yeni oyun oluştur** (new game) — on the game table this is the
   **Oyun ekle** button in the toolbar.
3. Type the game's name and choose **Oyunu kaydet** (save game).

Each game is one row of the game table. The toolbar above it lets you search,
switch between *Devam Eden* (in progress), *Tamamlanan* (finished) and *Tümü*
(all), order the games by your own order or A–Z, and open the filters. The
**⋯** menu at its end fits a column to its content, resets the cell sizes and
exports the tasks.

When a game is done you can mark it with **Tamamlandı olarak işaretle** (mark as
finished) on its row. This is your decision alone: it changes no task and no
cell.

## Cells and tasks

Every game has six columns (cells): **3D Baskı**, **Kart**, **Mukavva**,
**Özel**, **Ödünç Parçalar** (borrowed parts) and **Notlar** (notes). The first
four feed the pools of the same names. **Ödünç Parçalar** is free text for parts
borrowed from another game and **Notlar** is free text for anything else;
neither holds tasks.

The **Eksik** (missing) column right beside the game's name is not a cell but a
view: it shows the unfinished tasks of that game's 3D Baskı, Kart, Mukavva and
Özel columns, in their own colours and with their counts, after each column's
name. A task finished in its own column leaves Eksik, and comes back if it is
reopened. Nothing is edited in Eksik; tasks are edited and finished in their own
cells. A cell holds text, and some words in that text are marked as **tasks**.

(The headings in the Excel file are different: there the same columns are
called *3D Print*, *Laminasyon*, *Mukavva* and *Özel* — see the import guide.)

- **Cell**: all the text in one column of a game.
- **Task**: a piece of work picked out of that text and tracked. It can have a
  colour, a quantity and production stages.

To open a task, click its words in the cell, or reach it with the keyboard and
press Enter. The menu that opens offers **Düzenle** (edit), **Tamamla**
(finish), **Eksik/hatalı bildir** (report missing or failed) and **Görevi metne
dönüştür** (turn the task back into text).

## Importing XLSX and CSV

In **İçe/Dışa Aktarma**, start with **Excel veya CSV dosyası seç** (choose an
Excel or CSV file).

- **Excel (.xlsx)**: the seven-column reference layout is expected (Oyun,
  3D Print, Laminasyon, Mukavva, Özel, Eksik, Ödünç Parçalar). Cell colours and
  rich text are read.
- **CSV (.csv)**: the `game`, `source_type` and `raw_text` columns are required.
  The separator may be a comma or a semicolon.

For the exact rules of both formats, an example file and examples of rejected
rows, see the [import guide](ornek-ice-aktarma.md).

When you choose a file the application shows a **summary**: how many raw cells
will be saved, how many game-name cells were found, how many cells each column
has. **No game, cell or task is created** at this step. **Taslak olarak kaydet**
(save as draft) stores only the raw cells, as a draft.

If you have imported the same file before, the application says so and asks
whether you still want a new draft.

## Reviewing, editing, confirming and removing drafts

Imports that are not confirmed yet stay in the **Devam eden içe aktarmalar**
(imports in progress) list. You can carry on where you left off even if the
source file has since been deleted or changed.

On the review screen, for each raw cell you:

- select the part of the text that becomes a task;
- choose the task's pool, quantity and colour;
- accept or change the application's suggestions (a number at the start may be
  the quantity, `**` may mean "done", familiar colour names).

Suggestions are only suggestions: none of them is applied to anything until you
confirm.

**Onayla** (confirm) writes the tasks, the cell texts and the games in one
single operation. Right before confirming, the application takes a backup on its
own; if that backup cannot be taken, the confirmation does not start at all.

A draft you do not want can be deleted with **Kaldır** (remove); this deletes
only that draft's rows.

Drafts whose records contradict each other are shown in a separate section,
with a warning, and offer only **Kaldır**. Such a draft cannot be confirmed.

## Taking back a confirmed import

From the **Onaylanmış içe aktarmalar** (confirmed imports) list, **Geri al**
(take back) removes everything one import created, in one go. The application
first shows what will happen: how many tasks will be removed and how many cells
will return to their earlier text.

Taking back is **all or nothing**; single tasks cannot be picked. It is refused,
and nothing changes, when:

| Situation | Why |
| --- | --- |
| One of the tasks it created was edited later | So your own work is never undone |
| The text of one of the cells it wrote changed later | For the same reason |
| The import has already been taken back | It cannot be taken back twice |
| The cells' earlier text was not recorded (an old import) | A safe take-back cannot be proven |
| The import's record trail is incomplete | Which tasks belong to it cannot be told safely |

Games' "finished" marks are not taken back; you can remove them yourself on the
game table.

## Task progress, finishing and turning a task back into text

- **Tamamla / Yeniden aç** (finish / reopen): marks the task done, or takes that
  back. In the **3D Baskı** pool each unfinished task also has a **Tamamla**
  button that finishes it with a single press.
- **Eksik/hatalı bildir** (report missing or failed): records how many 3D prints
  came out wrong; you close it later with **Eksik giderildi** (resolved).
- **Stages**: card and board tasks keep the next production stage (print,
  lamination, cutting) and how many have passed it.
- **Görevi metne dönüştür** (turn into text): leaves the word in the cell as
  plain text; its colour, quantity and stages are no longer kept as a task. This
  cannot be undone; the production history is not deleted, but the task leaves
  the task lists.

A note added to a task is shown in brackets beside the task's name and count,
in its own column and in Eksik.

## Search and pool filters

On the game table, the search box searches game names and cell texts. On the
pool screens tasks are grouped by colour and state:

- the **Renk seçilecek** (colour to be chosen), **Tek renkli** (single colour)
  and **Tek öge çok renk** (one item, several colours) sections;
- for each group, the number of tasks, the total quantity, and how many missing
  and failed records there are.

Search and filters change only what you see on screen; they never touch your
data or the exported file.

## Exporting to CSV

**Ayarlar → İçe/Dışa Aktarma → Görevleri CSV’ye aktar** (export tasks to CSV),
or the **⋯** menu in the game table's toolbar, writes every task to a single
file.

- The search and filters on screen do not change what the file covers: every
  task is always written.
- Columns: `game, column, task, pool, colors, required_quantity, status, notes`.
- The file is UTF-8 and starts with a BOM so Excel opens it correctly.
- The same data always gives the same file.
- Text beginning with `=`, `+`, `-` or `@` gets a single quote in front of it so
  spreadsheet programs do not take it for a formula. The text in your database
  does not change.

## Making a backup by hand

**Ayarlar → Yedek oluştur** (make a backup): all your data is written to one
`.json` file. The file is named `pnp-yedek-<date>.json` by default and saved in
the folder you choose — an external disk works too.

A backup holds the games, cell texts, tasks, colours, production progress, the
history and the import records.

## Restoring a backup, and the safety backup

**Ayarlar → Yedekten geri yükle** (restore from backup) lets you choose a backup
file. The application:

1. checks the file (a damaged or incomplete file, or one from another
   application, is refused and nothing is touched);
2. asks what will happen: the chosen backup **replaces all of your data**;
3. when you choose **Geri yükle** (restore), first writes a safety backup of
   your current data into the backup folder, named `pnp-oncesi-…json`;
4. restores only after the safety backup has been written.

If anything goes wrong, your data stays as it was before, and the screen tells
you the safety backup's name.

A backup whose records contradict each other is refused before any question is
asked; your data stays as it is.

## How many automatic backups are kept

The application takes a backup by itself before some operations: before an
import is confirmed, before a restore, and before the data file is moved to a
new version.

In **Ayarlar → Otomatik yedeklerin saklanması** (keeping automatic backups) you
choose how many are kept (1–50, 7 by default). The number applies to each of the
three kinds separately. Backups you make yourself do not count towards it and
are never deleted by the application. Lowering the number does not delete files
there and then.

## After an unexpected shutdown

If the computer shuts down or the application ends unexpectedly, you carry on
where you left off when you open it again. A write that was cut short is either
fully written or not written at all; there is nothing in between. An import
draft that was not confirmed stays in the **Devam eden içe aktarmalar** list.

The application opens no "recovery" screen, leaves no marker file and asks you
to repair nothing.

## If the "Veri dosyanızda bir hasar bulundu" screen appears (the data file is damaged)

Every time it starts, the application quickly checks the data file. If the file
is damaged it shows a screen titled **PNP açılamadı** (PNP could not be opened)
and does **not** open the data file.

In that case:

1. **Do not delete, move or try to repair** the data file or your backup
   folder. The application does none of these either.
2. The backups in your backup folder stay exactly as they are.
3. You can carry on with your newest backup in a new installation; keep a copy
   of the damaged file before you delete it.
4. When asking for help, the sentence on the screen and the last lines of the
   diagnostic log below are enough.

## Diagnostic logs

When something goes wrong, the application writes a short, non-technical log
line:

```
$XDG_STATE_HOME/pnp-tracker/logs/
```

(On Windows, under `%LOCALAPPDATA%\pnp-tracker\state\`.)

These logs take at most 5 files × 1 MiB and the oldest file is deleted by
itself. They contain **no game name, task text, note, file name, path, user
name, identifier, SQL or error message**: only what kind of limit was reached,
in which operation, and the error's class name. A session in which nothing goes
wrong writes no line at all.

## Keyboard and accessibility

- **Tab** and **Shift+Tab** move through every screen; focus comes back to the
  navigation and never gets stuck anywhere.
- **Enter** or **Space** opens the selected item; in the table, **Enter** or
  **F2** starts editing a cell.
- **Esc** closes an open menu, panel or question and changes nothing.
- **Ctrl+Enter** saves in editing panels.
- In a cell editor, **Enter** saves and **Ctrl+Enter** starts a new line. Type a
  new name into a task column and press **Enter**, and the task window opens.
- In questions that cannot be undone, focus starts on **Vazgeç** (cancel).
- The application works in a small window (640×460) and with large system
  fonts; no information is given by colour alone, every state has words.

## Running the Linux portable archive

You do not need to install Java; the runtime is inside the archive.

```bash
tar -xzf pnp-tracker-<version>-linux-x86_64.tar.gz
cd pnp-tracker-<version>
./bin/pnp-tracker
```

You can unpack the archive into any folder. The application does not write to
the folder it runs from; your data still goes to your XDG folders. Deleting the
folder does not delete your data.

## Installing, updating and removing the Garuda/Arch package

```bash
sudo pacman -U pnp-tracker-<version>-1-x86_64.pkg.tar.zst   # install and update
pnp-tracker                                                  # open from the command line
sudo pacman -R pnp-tracker                                   # remove
```

The application is installed under `/opt/pnp-tracker`, the launcher is
`/usr/bin/pnp-tracker`, and it appears in the menu as **PnP Üretim Takipçisi**.
Updating means installing the new version's package with the same `pacman -U`
command; your data stays where it is.

## Installing on Windows

The Windows installer, `pnp-tracker-<version>-windows-x86_64.exe`, is one file
with the Java runtime inside it; you do not need to install Java.

- Run the installer. It installs for your user only (no administrator rights),
  lets you choose the folder, and adds a Start menu entry and a desktop
  shortcut named **PnP Üretim Takipçisi**.
- The installer is **not signed**, so Windows SmartScreen may warn that it comes
  from an unknown publisher. Check the file against `SHA256SUMS` from the same
  release before running it (see the README).
- Updating means running the newer installer. Uninstall from Windows'
  *Installed apps*; your data in `%LOCALAPPDATA%` and `%APPDATA%` stays.
- The installer is built and checked automatically on GitHub's Windows runner,
  but it **has not yet been tried by hand on a real Windows 11 installation**.

## Why your data stays when the package is removed

The package installs only the application's own files (`/opt/pnp-tracker`, the
launcher, the menu entry, the icon) and removes only those. Your data is not
where the application is installed but in your own user folders, which a package
manager does not touch. So when you remove and reinstall, you carry on where you
left off.

Deleting the data as well is a **separate job, done by hand**:

1. First take a backup with **Yedek oluştur** and copy it somewhere else.
2. Before deleting, see that the path is the right one:

   ```bash
   ls -la "${XDG_DATA_HOME:-$HOME/.local/share}/pnp-tracker"
   ```

3. Only after seeing the list and being sure it is the right folder, delete it
   with your own file manager. The settings and diagnostic folders can be
   checked and deleted the same way.

## Known limits

- Linux: `x86_64` with glibc; the interface uses X11, so a Wayland session needs
  XWayland.
- Windows: an `x86_64` installer is published, but it has not yet been tried on
  a real Windows 11 machine. There is no macOS version.
- One user, one copy: two windows of the application cannot run on the same
  data at the same time. A second copy does not open, and says so.
- No sync, no cloud, no multiple devices; a backup is the way to move data.
- The application's interface is in Turkish.

## Licence

The application's own source code is under the MIT licence; the text is in the
[`LICENSE`](../LICENSE) file at the root of the repository. In the installed
package the same text is at `/usr/share/licenses/pnp-tracker/LICENSE` and
`/opt/pnp-tracker/LICENSE`.

The Java runtime and the third-party libraries that come with the application
are distributed under their own licences; the MIT licence does not cover them.
Which components come with it, and where their licence texts are, is written in
the `THIRD_PARTY_NOTICES.md` file inside the package.
