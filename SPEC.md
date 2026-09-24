# Meal Mapper: Build Spec (v1)

Personal Android app. One user. No backend. No login.
It logs food to Health Connect. Google Health and Samsung Health read from Health Connect.

## 0. User and context (read this first)

One user: 48, Mumbai, eggetarian household (vegetarian + eggs; no meat, no fish).
Mostly home food: dal, sabzi, roti/phulka, rice, poha, upma, idli/dosa, eggs, chai, curd.
Eats out sometimes (restaurant food is oilier than home food). Buys Indian packaged food.
Every default in this app is Indian: food names, portions, units, labels, meal times, time zone (IST).

## 1. Scope

In v1, three capture modes. Every mode has an optional **context text field**
("2 phulkas, no ghee", "restaurant", "half the plate", "Amul Taaza, 200 ml").
The context goes to the lookup/prompt and is saved with the entry.

1. **Barcode**: live camera scan (or typed number) -> Open Food Facts lookup -> nutrition per serving.
   Not found (common for Indian products) -> offer "Photograph the label" in one tap.
2. **Camera**: take a photo now. User picks Meal or Label.
3. **Upload**: pick an existing photo from the gallery (Android Photo Picker, no storage permission).
   User picks Meal or Label.

Meal photo -> Gemini estimate. Label photo -> Gemini reads the printed table (exact numbers).

4. Review screen: user edits every number and the portion before save.
5. Save -> one `NutritionRecord` in Health Connect, with meal type.
6. Local history (last 30 days) with "log again".

Not in v1: accounts, cloud sync, charts, goals, streaks, recipes, social, ads, Play Store listing.
Google Health and Samsung Health already do charts and goals. Do not rebuild them.

## 2. Stack

| Layer | Choice | Reason |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 | Native. Health Connect SDK is Kotlin-first. |
| Camera | CameraX | Standard. |
| Barcode | ML Kit Barcode Scanning (bundled model) | On-device, free, offline, fast. |
| Food DB | Open Food Facts API v2 (`/api/v2/product/{code}`) | Free, no key. Set a custom `User-Agent: MealMapper/1.0 (email)`. |
| Vision | Gemini API, REST, structured JSON output | Cheap, good vision. Pin the model ID in one constant. |
| Health | `androidx.health.connect:connect-client` | Single write target for both health apps. |
| Storage | Room (history), DataStore + Android Keystore (API key) | Local only. |
| Networking | Ktor client or Retrofit + kotlinx.serialization | Pick one. Do not mix. |
| DI | Manual (one `AppContainer`) | App is small. Hilt is not necessary. |
| Build | Gradle, GitHub Actions -> signed APK artifact | No Android Studio needed on the build path. |

minSdk 28, targetSdk current. One `:app` module.

## 3. Architecture

```
ui/            Compose screens + ViewModels (Capture, Review, History, Settings)
domain/        NutritionEstimate (data class), Portion math, validation
data/off/      OpenFoodFactsClient + mapper -> NutritionEstimate
data/gemini/   GeminiClient + prompt + JSON schema -> NutritionEstimate
data/health/   HealthConnectWriter (permissions, write, availability check)
data/history/  Room DB (MealEntry), DAO
```

All three sources map to one type: `NutritionEstimate`. The Review screen and the writer only know this type.

```kotlin
data class NutritionEstimate(
    val name: String,
    val source: Source,            // BARCODE, MEAL_PHOTO, LABEL_PHOTO
    val servingGrams: Double?,     // null if unknown
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val saturatedFatG: Double?,
    val sugarG: Double?,
    val fiberG: Double?,
    val sodiumMg: Double?,
    val confidence: Confidence,    // HIGH (barcode/label), MEDIUM, LOW (photo)
    val items: List<String> = emptyList() // photo: detected components
)
```

## 4. Key rules

- Never save without the Review screen. The user confirms every save.
- Show the source and confidence on the Review screen. A photo estimate is an estimate.
- Portion control on Review: grams field and quick multipliers (0.5x, 1x, 1.5x, 2x). All macros scale from per-100 g values.
- Energy check: warn if `4*protein + 4*carbs + 9*fat` differs from kcal by more than 15%.
- Gemini prompt: return JSON only, validated against a schema. Include the user's context text verbatim.

## 4a. India rules

- Reference data: estimates follow IFCT 2017 (NIN, Hyderabad) values for Indian foods, not USDA defaults.
- Components: the prompt asks for each visible component with grams, plus the cooking fat (ghee/oil, in tsp) as its own line. Tadka and ghee are the biggest error source.
- Home vs restaurant: default is home cooking. If context says restaurant/hotel/dhaba, or the photo looks like one, assume more oil and bigger portions.
- Diet: never assume meat or fish. Ambiguous protein -> paneer, soya, egg, dal. If the photo clearly shows meat, say so and do not guess.
- Units on Review: grams plus Indian household units: katori (150 ml), roti/phulka (count), tsp/tbsp ghee, cup (chai, 150 ml), glass (250 ml), piece.
- Packaged labels (FSSAI format): read per 100 g and per serving; prefer per 100 g and scale. Handle "Energy (kcal)", "Total Sugars", "Added Sugars", "Sodium (mg)". Label text may be English or Hindi.
- Barcodes: Indian products start with 890. Try Open Food Facts first; fall back to label photo.
- Meal types (IST, Mumbai habits): breakfast 05:00-11:00, lunch 11:00-16:00, snack 16:00-20:30, dinner 20:30-05:00. User can change it.
- Frequent items (chai, phulka, dal) get "log again" from history. This is the most-used path.
- Health Connect write: set `name`, `mealType` (default from time of day), start/end time, and all non-null nutrients. Keep the returned record ID in history so "delete" also deletes it from Health Connect.
- API key: user pastes it on the Settings screen once. Store encrypted. Never commit it. Never put it in `BuildConfig`.
- Offline: barcode scan works; lookup queues are not in v1. Show a clear error and a retry button.

## 5. Health Connect checklist

- Manifest: `android.permission.health.WRITE_NUTRITION` (and `READ_NUTRITION` only if history reads back).
- Declare the permissions-rationale activity (`ACTION_SHOW_PERMISSIONS_RATIONALE`) and, on Android 14+, the `VIEW_PERMISSION_USAGE` activity-alias. The permission dialog fails without these.
- Check `HealthConnectClient.getSdkStatus()` before any call.
- Samsung Health: in Samsung Health > Settings > Health Connect, allow Nutrition read. Test this on day 1.

## 6. Design

- Dark and light themes from Material 3 dynamic color. No custom gradients, no emoji icons.
- Home = three large choices: Barcode / Camera / Upload. Context field on each capture screen.
- One primary action per screen. Numbers in a tabular font.
- Every error says what happened and what to do next.

## 7. Build order (each step ends in a working APK)

1. Skeleton: Compose app, theme, navigation, CI builds a debug APK.
2. Health Connect: permission flow + write a hard-coded record. Confirm it shows in Google Health and Samsung Health. **Gate: if Samsung Health does not show it, stop and decide.**
3. Barcode: CameraX + ML Kit + Open Food Facts + Review + save.
4. Camera + Upload with Label mode: Gemini label reading into the same Review screen.
5. Meal mode (camera + upload) with India prompt and component list.
6. History: Room, log again, delete (also from Health Connect).
7. Polish: icons, empty states, error states, signed release APK.

## 8. Tests

- Unit: OFF mapper (per-100 g and per-serving cases, missing fields), portion scaling, energy check, Gemini JSON parsing (good, partial, malformed).
- Instrumented (manual on device): permission flow, one write per source, visible in both health apps.
