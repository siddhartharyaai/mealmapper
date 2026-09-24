# Meal Mapper: Build Spec (v1)

Personal Android app. One user. No backend. No login.
It logs food to Health Connect. Google Health and Samsung Health read from Health Connect.

## 1. Scope

In v1:

1. Barcode scan -> Open Food Facts lookup -> nutrition per serving.
2. Meal photo -> Gemini vision -> nutrition estimate.
3. Label photo -> Gemini vision -> exact numbers from the printed nutrition table.
   (Fallback when the barcode is not in Open Food Facts. This happens often for Indian products.)
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
- Gemini prompt context: Indian home cooking, eggetarian household. Ask for visible components, estimated grams per component, and assumed cooking fat (ghee/oil). Return JSON only, validated against a schema.
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
- Home = camera, with a 3-way switch: Barcode / Meal / Label.
- One primary action per screen. Numbers in a tabular font.
- Every error says what happened and what to do next.

## 7. Build order (each step ends in a working APK)

1. Skeleton: Compose app, theme, navigation, CI builds a debug APK.
2. Health Connect: permission flow + write a hard-coded record. Confirm it shows in Google Health and Samsung Health. **Gate: if Samsung Health does not show it, stop and decide.**
3. Barcode: CameraX + ML Kit + Open Food Facts + Review + save.
4. Label photo: Gemini label reading into the same Review screen.
5. Meal photo: Gemini meal estimate.
6. History: Room, log again, delete (also from Health Connect).
7. Polish: icons, empty states, error states, signed release APK.

## 8. Tests

- Unit: OFF mapper (per-100 g and per-serving cases, missing fields), portion scaling, energy check, Gemini JSON parsing (good, partial, malformed).
- Instrumented (manual on device): permission flow, one write per source, visible in both health apps.
