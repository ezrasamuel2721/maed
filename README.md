# ማዕድ (MAED) V1.5 — Android app + Raspberry Pi acquisition service

Package: `et.maed.app` · Kotlin + Jetpack Compose · minSdk 24 / targetSdk 34.

## Status (honest)
- The validated AI model is **not enabled** until qualified lab-referenced samples meet the validation gate.
- When no validated model is available, MAED uses **Food Safety Screening**: LOW / MEDIUM / HIGH screening risk.
- Screening is **not proof of CLEAN or CONTAMINATED**. A HIGH result does not mean “contaminated.”
- No fake sensor data. Pi unreachable → `SENSOR_NOT_CONNECTED`; missing readings remain null.
- Camera alone cannot confirm pathogens/toxins. **BME688 is a VOC/gas-pattern sensor, not a pathogen detector.**

## App features
📷 ምግብ መርምር → camera → preview → food type + storage → real Pi acquisition → validated result when available, otherwise screening. Dataset stores sample ID, food type, storage, timestamp, temperature/humidity, RGB image, AS7341, BME688, ground truth, AI result, model status, sensor/acquisition status, and data source.

## Food Safety Screening
The screening engine requires real sensor data and at least 3 reference measurements. It prefers references from the same food type and storage condition. It uses robust median/MAD baselines, log-transformed gas resistance, and weighted AS7341/BME688 features to reduce sensitivity to outliers.

Levels:
- 🟢 LOW SCREENING RISK
- 🟡 MEDIUM SCREENING RISK
- 🔴 HIGH SCREENING RISK

These levels indicate deviation from the reference sensor pattern only. They do not diagnose pathogens, toxins, or contamination.

## Hardware: Pi 5 + Camera Module 3 + AS7341 + BME688
AS7341 and BME688 use I2C (SDA GPIO2, SCL GPIO3, 3V3, GND; AS7341 normally 0x39; BME688 normally 0x76/0x77). Camera uses CSI. Enable I2C/camera with `raspi-config`.

Install the Pi service dependencies, set `MAED_TOKEN`, then run `python3 maed_pi_service.py`. Keep the service on the LAN; do not expose plain HTTP to the internet.

### Controlled inspection chamber
Use a light-blocking enclosure, fixed LED lighting, fixed camera distance, fixed sample position, fixed AS7341 position, and BME688 in the sample headspace. Keep acquisition timing and warm-up fixed. Record an empty-chamber/white-reference calibration before a session. This improves repeatability; it does not make the sensors a validated food-safety test by itself.

## API (Pi)
Header `X-MAED-Token: <token>` is required.
- `GET /v1/status`
- `GET /v1/acquire`
- `GET /v1/diagnostics`

Diagnostics always reports `food_verdict: UNCERTAIN`; it cannot create CLEAN/CONTAMINATED labels.

## Validation
Pipeline: Sample → Dataset → qualified-lab ground truth → training → blind validation → GO/NO-GO. Only after successful validation can MAED enable CLEAN/CONTAMINATED verdicts. Never intentionally contaminate food at home.

## Build
The repository contains a GitHub Actions Android build workflow. The APK is produced by the successful workflow after each push to `main`.
