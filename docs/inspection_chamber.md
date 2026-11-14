# MAED Controlled Inspection Chamber — V1

## Purpose
A repeatable enclosure for collecting camera, AS7341 and BME688 measurements for the MAED dataset.

**Important:** this chamber does not determine whether food is safe or contaminated. It standardizes measurements so that later lab-labelled data can be used for model training and validation.

## Recommended first prototype

- Enclosure: opaque box, approximately 30 × 30 × 30 cm internal space.
- Interior: matte, non-reflective surface to reduce uncontrolled reflections.
- Camera: fixed mount at the same height and distance for every sample.
- Lighting: fixed LED source with constant brightness and fixed position.
- AS7341: fixed mount, aimed at the same sample area every time.
- BME688: positioned in the sample headspace without touching the food.
- Sample holder: fixed center position and repeatable orientation.
- Cable routing: controlled openings so outside light does not enter.
- Access door: close completely during acquisition.

The dimensions above are a prototype starting point, not a validated scientific specification.

## Measurement sequence

1. Start the Pi service and verify all sensors are connected.
2. Keep the chamber empty and record a blank/reference measurement.
3. Record the white reference used for optical consistency.
4. Place one sample in the fixed holder.
5. Close the chamber.
6. Allow the same warm-up/equilibration period for every sample.
7. Acquire camera + AS7341 + BME688 data.
8. Save the acquisition timestamp and procedure timings.
9. Remove the sample and clean/reset the holder according to the approved protocol.
10. Repeat using the exact same procedure.

Do not change lighting, camera distance, sensor position, acquisition timing, or sample placement between samples unless the change is explicitly recorded.

## Data required per sample

- sample_id
- food_type
- storage_condition
- collected_at
- chamber_id
- chamber_protocol_version
- camera image
- AS7341 channels
- BME688 temperature/humidity/pressure/gas resistance/heater information
- acquisition_status
- sensor_status
- data_source
- blank/reference measurement ID
- procedure timing
- laboratory ground truth, when available
- laboratory report/reference number, when available

## Ground truth

Use only:
- not_yet_available
- clean_per_protocol
- contaminated_per_protocol

CLEAN or CONTAMINATED labels must come from a qualified laboratory or approved food-safety testing protocol. Do not intentionally contaminate or culture food at home.

## First build

Before buying expensive equipment, build a simple enclosure prototype and verify:

- camera framing is identical across repeated measurements;
- AS7341 position is fixed;
- BME688 position is fixed;
- lighting is repeatable;
- outside light is blocked;
- blank/reference readings can be saved;
- repeated measurements of the same reference are reasonably stable.

Only after this repeatability check should we begin the labelled dataset campaign.

## Next hardware step

Minimum prototype:
1. opaque box
2. fixed LED light
3. camera mount
4. AS7341 mount
5. BME688 mount
6. fixed sample holder
7. Raspberry Pi 5
8. safe low-voltage power arrangement

Do not expose mains voltage inside the chamber. Use a properly enclosed, certified low-voltage power supply and keep electrical parts away from food and cleaning liquids.
