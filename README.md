# ማዕድ (MAED) V1.5 — Android app + Raspberry Pi acquisition service

Package: `et.maed.app` · Kotlin + Jetpack Compose · minSdk 24 / targetSdk 34.

## Status (honest)
- **No AI model exists. `MODEL_VALIDATED = false`** (`Model.kt`). The app never says Safe/Unsafe; result is always **🟡 UNCERTAIN**.
- **No fake sensor data.** Pi unreachable → `SENSOR_NOT_CONNECTED`; reachable but no readings → `NO_REAL_SENSOR_DATA`. Values stay `null`.
- Camera alone cannot confirm pathogens/toxins. **BME688 is a VOC/gas-pattern sensor, not a pathogen detector.**

## App features
📷 ምግብ መርምር → camera → preview → food type + storage → real Pi acquisition → result (UNCERTAIN). Dataset stores sample ID, food type, storage, timestamp, temperature/humidity, RGB image, AS7341, BME688, ground truth, AI result, model status, sensor/acquisition status, and data source. Ground truth has 3 options: Not yet available, CLEAN — lab/protocol confirmed, CONTAMINATED — lab/protocol confirmed. A lab reference can be saved for each sample.

## Hardware: Pi 5 + Camera Module 3 + AS7341 + BME688
AS7341 and BME688 use I2C (SDA GPIO2, SCL GPIO3, 3V3, GND; AS7341 normally 0x39; BME688 normally 0x76/0x77). Camera uses CSI. Enable I2C/camera with `raspi-config`.

Install the Pi service dependencies, set `MAED_TOKEN`, then run `python3 maed_pi_service.py`. Keep the service on the LAN; do not expose plain HTTP to the internet.

### Controlled inspection chamber
Use a light-blocking enclosure, fixed LED lighting, fixed camera distance, fixed sample position, fixed AS7341 position, and BME688 in the sample headspace. Keep the acquisition timing and warm-up procedure fixed. Before a session, record an empty-chamber/white-reference calibration. This makes measurements repeatable; it does **not** make the sensors a validated food-safety test by itself.

## API (Pi)
Header `X-MAED-Token: <token>` is required.
- `GET /v1/status` → service/version.
- `GET /v1/acquire` → real camera + AS7341 + BME688 readings; missing readings remain null.
- `GET /v1/diagnostics` → **new hardware self-test**. It checks camera capture, AS7341 communication/readings, BME688 communication/readings and timing. It always reports `food_verdict: UNCERTAIN`; it cannot create CLEAN/CONTAMINATED labels.

### Hardware self-test
With the Pi and sensors connected:
```bash
curl -H "X-MAED-Token: YOUR_TOKEN" http://127.0.0.1:8080/v1/diagnostics
```
Expected healthy result:
- camera: `OK`
- AS7341: `OK`
- BME688: `OK`
- `food_verdict: UNCERTAIN`

If a component is absent, the response explicitly reports `SENSOR_NOT_CONNECTED` or `ERROR:<type>` rather than inventing a value.

## Validation
Pipeline: Sample → Dataset → qualified-lab ground truth → training → blind validation → GO/NO-GO. Target 100–300+ labelled samples. Measure sensitivity, specificity, precision, recall, F1 and especially false negatives. Only after successful validation can MAED connect a model and enable CLEAN/CONTAMINATED verdicts. Never intentionally contaminate food at home.

## Build
The repository contains a GitHub Actions Android build workflow. The APK is produced by the successful workflow after each push to `main`.
