# Import Guide and Examples

This document describes the file formats the application **really accepts**.
The rules here are taken from the application's own reader; the example CSV file
is in the repository and can be imported as it is:

- [`ornek-ice-aktarma.csv`](ornek-ice-aktarma.csv)

An import never creates games, cells or tasks by itself. The file is read, its
raw cells are saved as a draft, and everything is written only with your
confirmation.

The application's interface is in Turkish; screens and buttons are named here by
their Turkish labels.

## Two formats, one path

| | XLSX | CSV |
| --- | --- | --- |
| Columns | The seven-column reference layout, recognised from the header row | The `game`, `source_type`, `raw_text` headers |
| Sheets | There may be several worksheets; you are asked which one to read | One logical sheet; its name is the file name |
| Colour | Cell fill and rich-text colours are read | No colour |
| "Finished" hint | A green cell may give a hint | A green-cell hint **never** appears |
| Formulas | The cell's displayed value is read | Every cell is plain text |
| Encoding | Excel's own | Strict UTF-8; a single BOM is dropped |

Both formats end up in the same place: raw cells → draft → review → confirm.

## CSV rules

```text
required headers : game, source_type, raw_text
column order     : free; extra columns are ignored
separator        : comma or semicolon (found from the header row)
encoding         : UTF-8; a single leading BOM is dropped
empty rows       : skipped
cell text        : not trimmed, stored as it is
identical rows   : not de-duplicated
cells starting with = + - @ : stay plain text
```

These values are valid in the `source_type` column (case and surrounding spaces
do not matter):

| Value | Meaning | In Excel |
| --- | --- | --- |
| `GAME` | The row carries the game's name | Oyun |
| `THREE_D` | 3D printing work | 3D Print |
| `CARD` | Card / lamination work | Laminasyon |
| `BOARD` | Board work | Mukavva |
| `SPECIAL` | Other special parts | Özel |
| `MISSING` | A note about a missing part | Eksik |
| `BORROWED` | A part lent or borrowed | Ödünç Parçalar |

The Excel headings themselves may be written too: `Laminasyon` in the
`source_type` column means the same as `CARD`.

## Example CSV

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

This file gives two games and nine raw cells. None of them is written to the
database as a task until it is confirmed.

### Details in the example

- **Turkish letters**: `YEŞİL`, `SİYAH`, `Özel`, `Kumaş` are kept exactly as
  they are; no case conversion is done.
- **Quantity suggestion**: if the text starts with a number (`15 KIRMIZI**`),
  that number is suggested as the quantity. Numbers in the middle of the text are
  not quantities: `Ticket to Ride 1910` does not mean one thousand nine hundred
  and ten. `0` is not a quantity.
- **The `**` mark**: a hint meaning "this part is done". It is kept in the text
  as it is and not shown on the review screen. A single `*` is not a mark; `****`
  is two marks.
- **Colour names**: familiar names such as `KIRMIZI` (red), `YEŞİL` (green),
  `SİYAH` (black) and `BEYAZ` (white) produce a colour suggestion. It is only a
  suggestion; you choose the colour.
- **A cell containing a comma**: is put in quotes (`"15 KIRMIZI**, 19 YEŞİL"`).
- **A cell that looks like a formula**: `=1+1 yazan kart…` stays plain text with
  all of its characters; it is never calculated anywhere.
- **An empty cell**: `raw_text` cannot be left empty (see below). If a game has
  nothing in a column, leave that row out altogether.

## Examples of rejected rows

The application reports the error with its row number and **saves nothing** —
even an error on the file's last row makes the whole file invalid.

| Example | Result |
| --- | --- |
| `Harmonies,THREE_D` (a missing field) | The row does not have as many fields as the header: rejected as a ragged row |
| `Harmonies,,15 KIRMIZI` | A required value is empty: `source_type` cannot be empty |
| `Harmonies,3D_YAZICI,15 KIRMIZI` | Unknown `source_type`; the application does not guess |
| a `game,game,raw_text` header | The same required column twice |
| an `oyun,tur,metin` header | A required column is missing |
| `Harmonies,THREE_D,"15 KIRMIZI` | An unclosed quote |
| `Harmonies,THREE_D,15 "KIRMIZI"` | A quote inside a field that does not start with one |
| A header readable with both commas and semicolons | Both separators are possible: the file is rejected, nothing is guessed |

## Formula-injection protection, from the user's side

If you wrote something starting with `=`, `+`, `-` or `@` into a cell:

- **On import** the text is stored as it is. The application does not calculate
  it, change it or treat it as a formula.
- **On export** (Görevleri CSV’ye aktar — export tasks to CSV) the application
  puts a single quote in front of it when writing the file. So when you open the
  file in Excel or LibreOffice, the program does not take it for a formula and
  run it. Your text in the application does not change; the protection is only
  in the file.

## XLSX layout

The reference worksheet has seven columns and is recognised from its header
row:

```text
Oyun | 3D Print | Laminasyon | Mukavva | Özel | Eksik | Ödünç Parçalar
```

- A row with something in the game-name column decides which game the work on
  the rows after it belongs to.
- Cell colours are read; a green game cell gives the hint "this game may be
  finished". The hint waits for your confirmation and is never applied by
  itself.
- Coloured pieces inside rich text are kept.
- Hidden sheets can be chosen too; the application says the sheet is hidden.
- If the header row is not recognised, the file is rejected and no cell is
  saved.

`app/src/desktopTest/resources/sample-import.xlsx` in the repository is a small,
anonymised example in this layout, and can be used for verification rounds.

## After importing

1. Open the draft on the **İçe/Dışa Aktarma** (Import/Export) screen.
2. In each raw cell, select the part that becomes a task; choose its pool,
   quantity and colour.
3. Choose **Onayla** (confirm). Right before confirming, the application takes a
   backup by itself.
4. If the result is not what you expected, you can take the whole import back
   with **Geri al** from the **Onaylanmış içe aktarmalar** (confirmed imports)
   list (the conditions are in the user guide).
