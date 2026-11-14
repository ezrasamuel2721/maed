# MAED Inspection Chamber — Hardware & Wiring V1

## Prototype hardware

1. Raspberry Pi 5 (8 GB recommended)
2. Raspberry Pi Camera Module 3
3. AS7341 spectral sensor breakout
4. BME688 environmental/VOC sensor breakout
5. Fixed white LED lighting module with stable low-voltage supply
6. Opaque enclosure, approximately 30 × 30 × 30 cm as a starting prototype
7. Non-reflective sample tray/holder
8. Fixed camera bracket
9. Fixed AS7341 bracket
10. BME688 bracket positioned in the sample headspace
11. Jumper wires/connectors and strain relief
12. Separate safe low-voltage power supply for lighting if needed

## Pi I2C wiring

AS7341 and BME688 share the Raspberry Pi I2C bus:

- Pi 3V3 -> sensor VIN/3V3 (only if the breakout explicitly supports 3.3 V)
- Pi GND -> sensor GND
- Pi GPIO2 / SDA -> AS7341 SDA and BME688 SDA
- Pi GPIO3 / SCL -> AS7341 SCL and BME688 SCL

Expected common addresses:
- AS7341: 0x39
- BME688: commonly 0x76 or 0x77

Confirm the actual breakout-board voltage requirements and address before powering it.

## Camera

Connect Camera Module 3 to the Pi CSI camera connector using the correct Raspberry Pi camera ribbon cable orientation. Keep the camera mount fixed; do not hand-hold it during measurements.

## Lighting

Use one fixed white LED arrangement. The light source must:
- remain at the same position;
- use the same brightness setting;
- illuminate the sample consistently;
- be shielded from the camera if direct glare occurs.

Do not use changing room light as the measurement source.

## Chamber layout

Top/front view concept:

CAMERA
   |
   v
[ fixed LED ]       [ fixed LED ]
        \         //
         \ SAMPLE//
          [TRAY]
            ^
         AS7341

BME688 sits above or beside the sample in the enclosed headspace, without touching the food.

The exact geometry must be measured and recorded after the physical prototype is assembled.

## Electrical safety

- Do not put mains-voltage wiring inside the chamber.
- Keep power supplies and exposed electronics away from food and cleaning liquids.
- Use enclosed, certified low-voltage power supplies.
- Disconnect power before changing wiring.
- Do not allow the sample to touch electrical components.

## First verification

Before collecting labelled food data:

1. Enable I2C on the Pi.
2. Verify AS7341 is detected at the expected address.
3. Verify BME688 is detected at its actual address.
4. Verify Camera Module 3 captures an image.
5. Run the MAED acquisition endpoint.
6. Record 10 repeated measurements of the same reference target.
7. Check that camera framing, AS7341 readings and BME688 readings are saved.
8. Record the chamber geometry and protocol version.

A successful hardware test does not establish food safety or contamination detection. It only establishes that the measurement system is functioning and repeatable enough to proceed to controlled data collection.
