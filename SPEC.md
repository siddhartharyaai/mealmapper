# Meal Mapper: Build Spec (v2)

Personal Android app. One user. No backend. No login.
It logs food to Health Connect. Google Health and Samsung Health read from Health Connect.

**Core rule: the AI reads and recognises. The databank supplies the numbers. The user's stated portion wins.**

## 0. User and context (read this first)

One user: 48, Mumbai, eggetarian household (vegetarian + eggs; no meat, no fish).
Mostly home food: dal, sabzi, roti/phulka, rice, poha, upma, idli/dosa, eggs, chai, curd.
Eats out sometimes (restaurant food is oilier than home food). Buys Indian packaged food.
Every default in this app is Indian: food names, portions, units, labels, meal times, time zone (IST).

## 1. Scope

Three ways in. Nothing else.

1. **Barcode**: live scan or typed number -> Open Food Facts.
   Not found or no nutrition (common for Indian products) -> "Photograph the label" in one tap.
2. **Camera**: take a photo now.
3. **Upload**: pick photos from the gallery (Android Photo Picker, no storage permission).

Camera and Upload take one or more photos (plate + label is a valid pair).
The app decides meal vs label per photo; the user can override with one tap.

**Every mode has the "What and how much" field before analysis**, and it stays editable on Review.
Examples: "2 phulkas, 1 katori dal, 1 tsp ghee", "200 g paneer bhurji", "half the pack", "restaurant, shared with 2".

Then: Review -> Save to Health Connect -> History.

### Also in scope (small, high value)

- **Recent and favourites**: one tap to log chai / phulka / usual breakfast again. This is the most-used path.
- **History (30 days)**: edit or delete an entry; delete also removes it from Health Connect.
- **Meal time**: default now; can set "at lunch" or a time when logging late.
- **My measures** (Settings): my katori (ml), my cup, my glass, my roti (g). Set once; used everywhere.

### Not in scope

Goals, charts, streaks, water, weight, accounts, cloud sync, social, ads, Play Store listing.
Google Health does charts and goals. **Recipe builder is deferred**: add it only if logging
home dishes by name + katori proves inaccurate in real use (see section 9).

## 2. Portion rules

Priority, highest first. The Review screen shows which one was used for every item.

1. **User-stated amount** (typed in the field, or edited on Review). Always wins.
2. **Label / barcode serving** (packaged food).
3. **Visual estimate** by the AI. Marked "estimated" in amber. Never silently accepted as fact.

Units the app understands: g, kg, ml, l, katori, bowl, plate, cup, glass, tsp, tbsp, piece, roti/phulka count,
slice, "half", "quarter", "x2", fractions of pack or serving.
Unit -> grams uses: My measures first, then a per-food piece weight from the databank (INDB serving sizes),
then defaults: katori 150 ml, cup 150 ml, glass 250 ml, tsp 5 ml, tbsp 15 ml.
Volume -> grams uses a density per food category (dal/curry 1.0, rice 0.8, curd 1.03, oil/ghee 0.91).

## 3. Data architecture: where numbers come from

```
photos + "what and how much" text
        |
        v
[Gemini call 1: recognise]  -> JSON: items[{name, aliases, form: raw|cooked|fried|packaged,
        |                         amount:{value, unit, source: user|visual}, cooking_fat}]
        |                         NO nutrient numbers allowed in this schema.
        v
[Local databank search]      -> top 5 candidates per item (FTS, Hinglish aliases)
        |
        v
[Gemini call 2: pick]        -> for each item: one candidate ID from the list, or NONE.
        |                         Enum-constrained output: it cannot invent an ID or a number.
        v
[App computes]               -> grams x per-100 g values. Deterministic, unit-tested.
        |
        v
Review: each item shows "Dal tadka · INDB · 180 g (you said 1 katori)"; tap to change match or amount.
```

- **Label photo**: Gemini transcribes the printed table verbatim (per 100 g and per serving). The app
  validates: 4/4/9 energy check, per-serving x (100 / serving g) = per 100 g within 5%, physical bounds.
  Fails -> the user sees the numbers highlighted and fixes them. Here the AI reads, it does not estimate.
- **No databank match (NONE)**: Gemini may then give an estimate, shown as "AI estimate, not in databank"
  in amber. The user must confirm it. These cases are logged locally so we know what to add to the databank.
- **Cooking fat**: logged as its own line (ghee/oil from the databank), never hidden inside a dish.
  If the dish entry already includes fat (INDB recipes do), extra fat is only added when the user says so
  ("extra ghee on top").

## 4. Databank (offline, inside the APK)

| Source | Use | Licence | Notes |
|---|---|---|---|
| INDB 2024: 1,014 recipes | Indian home dishes, cooked, per 100 g + serving sizes | Paper CC BY; repo has no licence file | Personal use. Recompute fried items (below). |
| INDB / IFCT 2017: 1,095 ingredients | Raw Indian ingredients, oils, ghee, flours, dals | IFCT: ICMR-NIN | Lab-measured in India. Gold standard for raw. |
| USDA FoodData Central: Foundation + SR Legacy | Generic and international ingredients | Public domain (CC0) | Very reliable. |
| USDA FNDDS | International dishes "as eaten" (pasta, pizza, sandwiches) | Public domain (CC0) | For restaurant / non-Indian meals. |
| Open Food Facts | Packaged products, live by barcode | ODbL | Crowd-sourced: always shown as "check against pack". |

Not used: Nutritionix, FatSecret, Edamam (terms restrict storing data; pricing can change).
Considered: UK CoFID (Indian restaurant dishes); add only if licence is confirmed and a gap is proven.

### Build pipeline (`tools/fooddb/`, runs in CI, output committed as `app/src/main/assets/food.db`)

1. Download pinned versions of each source (checksum recorded).
2. Normalise to one schema: id, name, aliases, source, source_id, licence, basis (g/ml),
   per-100 values (kcal, protein, carbs, sugar, fat, sat fat, fibre, sodium), serving (label + g), quality.
3. **Recompute INDB recipes from their ingredient lines** (`recipes.xlsx`), not the published totals.
   Lines with "for frying" oil (e.g. poori: 80 g atta + 480 ml oil) are replaced by absorbed oil =
   a fat-uptake figure per fried-food class, taken from published measurements (poori: 28-30% oil,
   Fibres-in-poori study). Every corrected row stores the rule and citation.
4. Validation gates, each row: 4/4/9 energy within 15% (fibre at 2 kcal/g); macros <= 100 g per 100 g;
   kcal <= 900; cross-source check for foods present in two sources (flag if > 25% apart).
5. Quality flag per row: `verified` (passes all), `corrected` (recomputed, cited), `flagged` (excluded from
   auto-match; searchable with a warning).
6. Aliases: Hindi / Marathi / Gujarati / common spellings (dal/daal/dhal, bhindi/okra, dahi/curd).
   An LLM may propose aliases and classify fried/not fried at build time. **An LLM never writes a nutrient value.**
7. Report: counts per source and flag, and every change vs the published value, committed as `tools/fooddb/REPORT.md`.

Measured in September 2026 on INDB: 118 of 1,014 recipes have implausible fat (> 45 g / 100 g, not oil or ghee),
mostly deep-fried items where all frying oil is counted as eaten; 39 fail the 4/4/9 energy check.

## 5. Accuracy: how we know it works

**Golden set before step 5 ships**: 30 of the user's real meals, weighed on a kitchen scale, with ingredient
amounts noted. Target: total kcal within 15% for 80% of meals when the user states portions; report the
error without stated portions separately. Re-run after every prompt or databank change.
Plus 20 packaged products checked label-vs-app.

## 6. Stack

| Layer | Choice |
|---|---|
| UI | Kotlin, Jetpack Compose, Material 3 |
| Camera / barcode | CameraX + ML Kit barcode (bundled) |
| Photos | Android Photo Picker |
| AI | Gemini API over REST, structured JSON output with response schema. Model ID pinned in one constant, checked at build time. |
| Databank | SQLite asset + Room, FTS4 search |
| Health | Health Connect `connect-client` |
| Storage | Room (history, favourites, my measures), DataStore + Keystore (API key) |
| Networking | OkHttp + kotlinx.serialization |
| Build | GitHub Actions -> signed arm64 APK on the `latest-debug` release |

## 7. Health Connect

- Write one `NutritionRecord` per item (not one per meal): Google Health lists items by name, and edits stay simple.
- `name` = "Food · note" (max 100 chars), `mealType`, 1-minute interval at meal time, all non-null nutrients.
- `clientRecordId` = our history ID, so edit = upsert and delete = delete.
- Samsung Health: Samsung Health > Settings > Health Connect, allow Nutrition read. (Still to confirm on device.)

## 8. Design

- Material 3 dynamic colour, light and dark. No gradients, no emoji icons. Numbers in tabular figures.
- Home: three large choices (Barcode / Camera / Upload), then Recent and Favourites.
- One primary action per screen. Every error says what happened and what to do next.
- Amber = estimated or unverified. Never show an estimate in the same style as a measured value.

## 9. Build order (each step ends in a working APK)

1. ~~Skeleton, CI~~ done.
2. ~~Health Connect write; confirmed in Google Health~~ done. Samsung Health still to confirm.
3. ~~Barcode + Open Food Facts + Review + save~~ done. Add: "What and how much" field on the scan screen.
4. **Databank**: build pipeline + `food.db` + search screen + portions (units, My measures). Log any food by name.
5. **Label photo** (Camera + Upload): Gemini transcription + validation. Replaces typing label values.
6. **Meal photo** (Camera + Upload): recognise -> match -> compute, per section 3. Golden set gate.
7. History, Recent, Favourites, edit/delete.
8. Polish: empty states, error states, icon, release signing.

Deferred until real use shows a need: recipe builder, offline queue, CoFID.

## 10. Tests

- Unit: OFF parser, portion scaling, unit conversion, energy check, barcode check digit, Gemini JSON parsing
  (good, partial, malformed, invented ID rejected), databank validation rules, INDB recompute (poori fixture).
- Pipeline: REPORT.md diff reviewed on every databank change.
- Device: permission flow, one write per source, visible in Google Health (and Samsung Health).
