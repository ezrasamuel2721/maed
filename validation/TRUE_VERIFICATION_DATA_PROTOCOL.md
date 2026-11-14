# MAED True Verification Data Protocol

This directory defines the evidence required before MAED may emit a verified CLEAN/CONTAMINATED result.

## Non-negotiable rule

Do not add synthetic, guessed, copied, or manually invented sensor readings as validation samples.

A sample is eligible only when:
- it is a real physical food/drink sample;
- the sensor/camera capture is tied to that exact sample;
- the contamination status is established by an independent ground-truth procedure;
- the result is traceable to a sample ID and collection timestamp.

## Minimum validation gate

For the current MAED verifier:
- at least 20 eligible CLEAN samples;
- at least 20 eligible CONTAMINATED samples;
- 80/20 sample-ID split for blind evaluation;
- sensitivity >= 0.90;
- specificity >= 0.90;
- false negatives = 0.

These are minimum software gates, not a claim that the device is medically or legally certified.

## Required sample record

Each sample must have:
- sample_id
- food_type
- storage_condition
- collected_at
- ground_truth_status: CLEAN or CONTAMINATED
- ground_truth_method
- ground_truth_reference_id
- sensor capture values
- camera capture associated with the same sample, when camera validation is used

## Ground truth

The ground_truth_method and reference_id must identify the independent test/report used to establish the label. The MAED app must never manufacture this label.

## What the certificate means

A MAED verification certificate means that the software model passed its declared blind-validation gate on the supplied reference dataset. It is NOT a laboratory certificate for an individual food item and does not establish universal detection of every pathogen, toxin, adulterant, or chemical.

## Collection workflow

1. Assign a unique sample ID.
2. Collect and seal the physical sample according to the chosen test protocol.
3. Capture MAED sensor/camera data without changing the sample identity.
4. Obtain the independent ground-truth result.
5. Enter the result into the reference dataset.
6. Re-run blind validation.
7. Export the certificate only if the gate passes.

## Important limitation

AS7341, BME688, and a camera do not by themselves provide universal pathogen/toxin detection. If a target contaminant requires a dedicated analytical sensor or laboratory method, that target must be validated separately.
