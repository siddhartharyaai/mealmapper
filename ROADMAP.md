# Meal Mapper: Roadmap

Each phase ends with an APK at the same link and a test for the user. The next phase starts only
after the user reports the test result. Design details are in `SPEC.md`.

APK: https://github.com/siddhartharyaai/mealmapper/releases/download/latest-debug/meal-mapper.apk

| Phase | Size | What you get | Needs from you |
|---|---|---|---|
| 1 | Small | Portion note before barcode lookup; Settings (age, weight, calorie cap); daily ticker | Nothing |
| 2 | Small | History, edit/delete, meal time, Recent, Favourites | Nothing |
| 3 | Medium | AI set-up (Groq); **web product lookup** (barcode miss or product photo); label photos | Gemini API key |
| 4 | Large | Food databank (Indian + international, drinks, alcohol); log any food by name | 20 min of searching |
| 5 | Large | Meal photos: tap questions, ranges, katori + household fat calibration, house versions | 30 weighed meals over ~2 weeks |
| 6 | Medium | Restaurant meals: web lookup of restaurant and dish; chains' published values | 5 restaurant meals |
| 7 | Medium | Menu scanner + top-3 recommender | 2 menus |
| 8 | Small | Polish, release signing, Samsung Health confirmation | 10 min |

Order logic: small phases first when they are useful on their own (1, 2). Label photos (3) come before the
databank because they fix the biggest gap in barcode logging (Indian products missing from Open Food Facts)
and set up Gemini, which phases 5-7 reuse. Meal photos (5) need the databank (4). Restaurants (6) and the
menu recommender (7) build on meal photos.

## Phase 1: Small

Build
- Scan screen: "What and how much" field above the camera, before the lookup. Carried to Review.
- A local portion parser turns the note into an amount where it can: "125 g", "200 ml", "half pack",
  "2 servings", "1 glass", "1/2 pack". Anything else stays as a note on the entry.
- Settings: age, weight (kg), daily calorie cap (kcal). Health Connect setup moves under Settings.
- Home ticker: calories eaten today (all apps, from Health Connect), left or over the cap, protein/carbs/fat.
  Needs one new permission: read nutrition.

Test (15 min)
1. Settings: enter age, weight, cap. Close and reopen the app: values kept.
2. Home shows a banner to allow the new permission. Allow it.
3. Ticker shows today's total. Compare with Google Health > Food and drink: same kcal.
4. Scan a pack with note "half pack". Review shows half the pack weight and says it came from your note.
5. Scan with "2 servings" and with "150 g". Save one. The ticker goes up by that amount.
Pass: all five work; ticker matches Google Health.

## Phase 2: Small

Build
- History: last 30 days, grouped by day and meal. Tap to edit amount, name or meal; delete. Changes also
  update or remove the entry in Health Connect.
- Meal time: "now" by default; pick breakfast/lunch/snack/dinner or a time when logging late.
- Recent and Favourites on Home: one tap logs again (chai, phulka, usual breakfast).

Test (10 min)
1. Log 3 items. Edit one amount; check Google Health shows the new value.
2. Delete one; it disappears from Google Health.
3. Star one as favourite; log it again from Home with one tap.
4. Log something "at lunch" while it is evening; Google Health shows it under Lunch.
Pass: Google Health always matches the app after edit/delete.

## Phase 3: Medium

Build
- Settings: paste Gemini API key (stored encrypted). Billing must be on: Google Search grounding is not in
  Gemini's free tier for any 3.x model (pricing page, 24 Sep 2026); paid tier includes 5,000 searches/month.
  Model gemini-3.8-flash via the Interactions API (generateContent is marked Legacy); Groq was tried and dropped.
- **Web product lookup** (added after the Britannia Nutri Choice test, where Open Food Facts had the name but
  no values). Trigger: barcode not found / no values, or a photo of the front of a pack.
  Gemini with Google Search grounding finds the product's nutrition table on the maker's site and Indian
  retailers (BigBasket, Blinkit, Amazon.in). Rules:
  - Output schema: per-100 g values + serving size + source URLs. No source URL -> no numbers.
  - Variant check: the product name, pack size and flavour must match the pack (Nutri Choice has many variants).
  - Two sources agreeing within 10% -> "web, 2 sources". One source -> "web, 1 source, check the pack".
  - Same validation as labels: 4/4/9 energy, physical limits.
  - Result saved on the phone by barcode: the next scan of the same product is instant and identical.
- Label photo stays as the most accurate option ("the pack in your hand beats the internet").
- Camera and Upload (gallery, several photos) with the "What and how much" field.
- Label reading: Gemini copies the printed nutrition table (per 100 g and per serving; English/Hindi).
  The app checks: calories vs macros, per-serving vs per-100 g, physical limits. Failures are highlighted.
- Barcode "not found" -> one tap to photograph the label.
- Meal photos are not in this phase: a meal photo gets "Meal photos come in phase 5".

Test (20 min)
1. 10 Indian packs, including ones Open Food Facts did not know. Photograph each label.
2. Compare every number on Review with the pack.
Pass: 9 of 10 read correctly on the first try; wrong reads are highlighted, not silent.

## Phase 4: Large

Build
- `tools/fooddb/` pipeline: INDB (recomputed from ingredient lines, fried-food oil fixed), IFCT 2017,
  USDA Foundation + SR Legacy + FNDDS. Validation gates, quality flags, `REPORT.md`.
- Drinks, alcohol (7 kcal/g; peg 30/60 ml, beer 330/500/650 ml), mithai, chocolate, namkeen.
- Hinglish search: dal/daal, bhindi/okra, dahi/curd.
- Log by name: search -> pick -> portion in katori, roti count, pieces, grams, ml, peg.

Test (20 min)
1. Search 20 foods you eat often (home, restaurant, drinks, one alcohol).
2. For each: is it found, and is the portion list sensible?
3. Read `REPORT.md` summary (I send you the key numbers): how many rows were corrected or flagged.
Pass: 18 of 20 found; no absurd values (e.g. poori is no longer 738 kcal/100 g).

## Phase 5: Large

Build
- Meal photos: recognise -> up to 2 tap questions -> match to databank -> compute. No invented numbers.
- Range display ("≈ 520 kcal, 450-600"); estimated items in amber.
- One-time calibration: katori and plate photo with a ₹10 coin; household oil and ghee per month.
- House versions: your corrections become "Home: dal" for next time.

Test (about 2 weeks, a few minutes a day)
1. Do the two calibrations.
2. 30 home meals: photograph, then someone weighs the served plate on a kitchen scale; note the weight.
3. I compare app vs weighed values and report accuracy.
Pass: true value inside the shown range for 80% of meals; single number within 20% for 70%.
Fail: we add one question per dish type for the cook (e.g. spoons of ghee in dal) and re-test.

## Phase 6: Medium

Build
- Restaurant mode: restaurant name (optional) + photo. Chains with published nutrition use those values.
  Others: Gemini web search for the menu and dish description, then the databank's rich/restaurant variant.
  Sources shown on Review.

Test (over a week)
1. 5 restaurant meals, at least one chain (e.g. McDonald's India).
Pass: chain item matches the chain's published value; others show sources and a sensible range.

## Phase 7: Medium

Build
- Menu scanner: photo of a menu -> dishes estimated -> top 3 picks that fit calories left today,
  eggetarian, with one-line reasons (protein, oil, portion).

Test
1. 2 real menus. Do the picks make sense for your day?
Pass: picks are eggetarian, within calories left, and you would actually order at least one.

## Phase 8: Small

Build
- Empty and error states, icon pass, release signing key kept out of the repo, Samsung Health check.

Test (10 min)
1. Fresh install from the link; full flow once per mode.
2. Samsung Health shows entries (if you use it).
Pass: no crashes, no dead ends.
