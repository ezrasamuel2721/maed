# ማዕድ (MAED) V1.5 — Android app + Raspberry Pi acquisition service

Package: `et.maed.app` · Kotlin + Jetpack Compose · minSdk 24 / targetSdk 34.

## Status (honest)
- **No AI model exists. `MODEL_VALIDATED = false`** (`Model.kt`). The app never says Safe/Unsafe; result is always **🟡 UNCERTAIN** — "መረጃ ተሰብስቧል፤ የተረጋገጠ የምግብ ደህንነት ሞዴል ገና አልተፈተነም።"
- **No fake sensor data.** Pi unreachable → `SENSOR_NOT_CONNECTED`; reachable but no readings → `NO_REAL_SENSOR_DATA`. Values stay `null`. Simulation mode is **not implemented** (nothing simulated can enter the dataset); the `data_source` field and export filter (`real` only) are in place for it.
- Camera alone cannot confirm pathogens/toxins. **BME688 is a VOC/gas-pattern sensor, not a pathogen detector.**
- Source code has **not been compiled** in the build environment (no Android SDK/Gradle/network). Expect to fix small compile errors on first build.

## App features
📷 ምግብ መርምር → system camera → capture (camera closes) → preview → food type + storage → sensor acquisition from Pi → result (UNCERTAIN) + Amharic TTS (`am-ET`; if the phone lacks that voice, text only + notice). 🖼️ ፎቶ ምረጥ gallery fallback. Sample record fields: sample_id, food_type, storage_condition, collected_at, temperature, humidity, RGB image, AS7341, BME688, ground_truth, ai_result, model_version, model_validated, risk_score, sensor_status, acquisition_status, data_source. Dataset: list, count (real vs simulated), ground-truth edit (3 options: Not yet available, CLEAN — lab/protocol confirmed, CONTAMINATED — lab/protocol confirmed; default *Not yet available*) plus lab-reference entry; both must come from a qualified lab/food-safety protocol, delete, JSON/CSV export (share sheet; images referenced by file name). Report form (stored locally, **not sent to any authority**). ☰ ሜኑ: 7 layer items — restaurants/menus/prices show **demo data only**; hotels, orders, nearby, owner registration are placeholders.

## Hardware: Pi 5 (8GB) + Camera Module 3 + AS7341 + BME688
Wiring: AS7341 and BME688 on I2C (SDA GPIO2, SCL GPIO3, 3V3, GND; distinct addresses 0x39 / 0x76 or 0x77). Enable I2C and camera (`raspi-config`). Camera on CSI.
Pi setup: `sudo apt install python3-picamera2 python3-pip`; `pip install -r pi/requirements.txt --break-system-packages`; `MAED_TOKEN=<secret> python3 pi/maed_pi_service.py`. Run as a systemd service for production. Phone and Pi must be on the same Wi-Fi; set URL/token in app ⚙️ ቅንብሮች.
Security: shared token header, LAN only, plain HTTP (cleartext enabled in manifest). Do not expose to the internet; add TLS before any wider use.

### Controlled inspection chamber (required for repeatable data)
Light-proof enclosure (outside-light isolation) · fixed LED lighting · fixed camera distance · fixed sample position · fixed AS7341 position · BME688 in the sample headspace · fixed acquisition procedure (warm-up, heater profile, wait time, order) · blank/reference calibration (empty chamber + white reference) before each session and logged. Record timing per stage; do not assume speed.

## API (Pi)
Header `X-MAED-Token: <token>` required (401 otherwise).
- `GET /v1/status` → `{"service":"maed-pi","version":"1.4"}`
- `GET /v1/acquire` → `{acquisition_status: COMPLETE|PARTIAL|NO_REAL_SENSOR_DATA, sensor_status:{camera,as7341,bme688: OK|SENSOR_NOT_CONNECTED|ERROR:<type>}, temperature_c, humidity_percent, as7341:{F1_415nm..F8_680nm,clear,nir}|null, bme688:{temperature_c,humidity_percent,pressure_hpa,gas_resistance_ohm,heat_stable,heater_c,heater_ms}|null, image_jpeg_base64|null, acquired_at, data_source:"real"}`
Driver notes: AS7341 via `adafruit_as7341` (set chamber LED/gain/integration to your hardware); BME688 via `bme680` single-heater profile (BSEC/multi-profile is a later improvement).

## Validation (before any 🟢/🔴)
Pipeline: Sample → Dataset → Ground truth (lab) → Training (Models A vision, B spectral, C VOC, D fusion) → Blind validation → GO/NO-GO. Target 100–300+ labelled samples; strict train/test split; unseen samples only. Record sensitivity, specificity, precision, recall, F1, false positives, false negatives — **false negatives (unsafe rated LOW) are the primary safety metric**. NO-GO → redesign. Only after GO connect a model in `verdict()` and set `MODEL_VALIDATED=true`. Use ordinary household food only for capture; never spoil/contaminate food at home; unsafe ground truth needs a lab protocol. Schema: `docs/sensor_data_schema.json`.

## Testing
1. Pi service without hardware: start it, `curl -H "X-MAED-Token: t" :8080/v1/acquire` → `NO_REAL_SENSOR_DATA`, nulls (verified in this build).
2. App with empty URL → sample saved with `SENSOR_NOT_CONNECTED`, UNCERTAIN, voice/notice.
3. App + Pi with real sensors → `COMPLETE`, values present. Check camera closes after capture, gallery fallback, export files open in a spreadsheet, delete works, GT edit persists after restart.

## Build
Needs JDK 17 + Android SDK 34 (Android Studio recommended: open this folder, let it sync).
- Wrapper: `gradle wrapper --gradle-version 8.7` (once).
- Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`; install with `adb install`.
- AAB: create a keystore (`keytool -genkeypair ...`), add a `signingConfigs.release` block to `app/build.gradle.kts`, then `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
