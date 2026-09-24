# Meal Mapper: Build Spec (v3)

Personal Android app. One user. No backend. No login.
It logs food to Health Connect. Google Health and Samsung Health read from Health Connect.

**Core rule: the AI reads and recognises. The databank supplies the numbers. The user's stated portion wins.**

## 0. User and context (read this first)

One user: 48, Mumbai, eggetarian household (vegetarian + eggs; no meat, no fish).
Mostly home food cooked by a **home cook**: the user does not know recipes or oil quantities.
Restaurants: the user can only give photos (and maybe the restaurant name). Buys Indian packaged food.
Logs everything: food, drinks, alcohol, chocolates, snacks.
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

- **Daily ticker** on Home: "1,240 of 1,900 kcal · 660 left", a thin bar, plus protein/carbs/fat so far.
  Totals come from Health Connect for today (all apps), so they match Google Health. Needs `READ_NUTRITION`.
- **Settings**: age, weight (kg), daily calorie cap. Age and weight feed the menu recommender and protein
  guidance; the cap drives the ticker. Plus the kitchen calibration in section 2a.
- **Clarifying questions**: when the AI is unsure about something that moves calories a lot, it asks at most
  two tap-to-answer questions ("1 or 2 katoris of dal?", "Ghee on the roti?"). No typing needed.
- **Recent and favourites**: one tap to log chai / phulka / usual breakfast again. This is the most-used path.
- **History (30 days)**: edit or delete an entry; delete also removes it from Health Connect.
- **Meal time**: default now; can set "at lunch" or a time when logging late.
- **Menu scanner + recommender (last step)**: photo of a restaurant menu -> each dish estimated from the
  databank -> top 3 picks that fit calories left today, eggetarian, with reasons.

### Not in scope

Charts, streaks, water, weight tracking, accounts, cloud sync, social, ads, Play Store listing,
**recipe builder** (the user does not cook; see 2a for how home food is calibrated instead).

## 2. Portion rules

Priority, highest first. The Review screen shows which one was used for every item.

1. **User-stated amount** (typed in the field, or edited on Review). Always wins.
2. **Label / barcode serving** (packaged food).
3. **Visual estimate** by the AI. Marked "estimated" in amber. Never silently accepted as fact.

Units the app understands: g, kg, ml, l, katori, bowl, plate, cup, glass, peg, bottle, tsp, tbsp, piece,
roti/phulka count, slice, "half", "quarter", "x2", fractions of pack or serving.
Unit -> grams uses: the calibrated katori/plate first (2a), then a per-food piece weight from the databank
(INDB serving sizes), then defaults: katori 150 ml, cup 150 ml, glass 250 ml, tsp 5 ml, tbsp 15 ml,
peg 30/60 ml.
Volume -> grams uses a density per food category (dal/curry 1.0, rice 0.8, curd 1.03, oil/ghee 0.91).

## 2a. Home cook and restaurant food: calibration without recipes

The user cannot supply recipes or oil amounts. Hidden fat (ghee, oil, butter, cream) is the largest
invisible error in Indian food and no camera can see it. Four low-effort ways to close the gap:

1. **Household fat budget (one question, once)**: litres of oil and kg of ghee the house buys per month,
   and how many people eat at home. The grocery bill or the cook knows this. The app turns it into an
   average fat per home meal and uses it to choose between light / normal / rich versions of a dish.
2. **Katori calibration photo (once)**: photograph your usual katori and plate next to a ₹10 coin
   (known size). Gemini estimates their volume; the user confirms. Used as the default size in every photo.
3. **House versions learn from corrections**: when the user corrects a home dish (match, portion, "richer"),
   the app saves it as "Home: dal" and matches future photos of that dish to the house version first.
4. **Show ranges, not false precision**: photo-only meals show "≈ 520 kcal (450-600)". The single number
   goes to Health Connect; the range tells the user how much to trust it.

**Restaurants** (user gives photo, maybe the name):
- If the name or bill mentions a chain that publishes nutrition (McDonald's India does), use the
  published values: that is factual data, cited on Review.
- Otherwise Gemini with Google Search grounding looks up the restaurant's menu and the dish description
  (e.g. "Dal Bukhara: slow-cooked urad with butter and cream"), then matches it to the databank's
  restaurant-style variant. The sources it used are shown on Review.
- Restaurant dishes default to the "rich" version and restaurant portion sizes unless the user says otherwise.

**Drinks and alcohol**: alcohol has 7 kcal per g and Health Connect has no alcohol field, so alcohol calories
go into total energy, and the energy check becomes 4P + 4C + 9F + 7A. Indian measures: peg 30 ml (small)
and 60 ml (large), beer 330 / 500 / 650 ml bottles, wine glass 150 ml. Chai, coffee, lassi, nimbu pani,
coconut water, cold drinks, chocolates and mithai are all first-class items in the databank.

## 3. Data architecture: where numbers come from

```
photos + "what and how much" text
        |
        v
[Gemini call 1: recognise]  -> JSON: items[{name, aliases, form: raw|cooked|fried|packaged|drink,
        |                         amount:{value, unit, source: user|visual}, richness: light|normal|rich,
        |                         setting: home|restaurant, questions[max 2]}]
        |                         NO nutrient numbers allowed in this schema.
        |                         Restaurant: Google Search grounding on, sources returned.
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
| Chain restaurant nutrition (e.g. McDonald's India) | Chain dishes | Published by the chain | Looked up live with grounding; cited. |
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
4. Validation gates, each row: 4/4/9/7 energy within 15% (fibre at 2 kcal/g, alcohol at 7); macros <= 100 g per 100 g;
   kcal <= 900; cross-source check for foods present in two sources (flag if > 25% apart).
5. Quality flag per row: `verified` (passes all), `corrected` (recomputed, cited), `flagged` (excluded from
   auto-match; searchable with a warning).
6. Aliases: Hindi / Marathi / Gujarati / common spellings (dal/daal/dhal, bhindi/okra, dahi/curd).
   An LLM may propose aliases and classify fried/not fried at build time. **An LLM never writes a nutrient value.**
7. Report: counts per source and flag, and every change vs the published value, committed as `tools/fooddb/REPORT.md`.

Measured in September 2026 on INDB: 118 of 1,014 recipes have implausible fat (> 45 g / 100 g, not oil or ghee),
mostly deep-fried items where all frying oil is counted as eaten; 39 fail the 4/4/9 energy check.

## 5. Accuracy: how we know it works

**Golden set before meal photos ship**: 30 real meals. The user photographs; someone weighs the served plate
on a kitchen scale (no recipe knowledge needed). Home meals: compare with the app's estimate.
Target: true kcal inside the shown range for 80% of meals, and the single number within 20% for 70%.
Restaurant meals are scored separately (no ground truth except chains). Re-run after every prompt or
databank change.
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
4. **Settings + daily ticker**: age, weight, calorie cap; today's totals from Health Connect. Small, useful at once.
5. **Databank**: build pipeline + `food.db` (incl. drinks, alcohol, mithai, chocolate) + search + portions.
6. **Label photo** (Camera + Upload): Gemini transcription + validation. Replaces typing label values.
7. **Meal photo** (Camera + Upload): recognise -> ask -> match -> compute; ranges; house versions;
   household fat budget and katori calibration. Golden set gate (section 5).
8. **Restaurant**: grounded lookup (chains' published values, menus), rich/restaurant defaults.
9. History, Recent, Favourites, edit/delete.
10. **Menu scanner + recommender**.
11. Polish: empty states, error states, icon, release signing.

Deferred until real use shows a need: offline queue, CoFID.

## 10. Tests

- Unit: OFF parser, portion scaling, unit conversion, energy check, barcode check digit, Gemini JSON parsing
  (good, partial, malformed, invented ID rejected), databank validation rules, INDB recompute (poori fixture).
- Pipeline: REPORT.md diff reviewed on every databank change.
- Device: permission flow, one write per source, visible in Google Health (and Samsung Health).
