#!/usr/bin/env python3
"""MAED Raspberry Pi 5 acquisition service (stdlib HTTP).
Never fabricates readings: a sensor that cannot be read is reported as SENSOR_NOT_CONNECTED / ERROR:<type> and its data is null.
Run: MAED_TOKEN=secret python3 maed_pi_service.py   (port 8080)
"""
import base64, io, json, os, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("MAED_TOKEN", "")
NC = "SENSOR_NOT_CONNECTED"

def read_camera():
    try:
        from picamera2 import Picamera2
    except Exception:
        return None, NC
    try:
        c = Picamera2(); c.configure(c.create_still_configuration()); c.start(); time.sleep(1.5)
        buf = io.BytesIO(); c.capture_file(buf, format="jpeg"); c.stop(); c.close()
        return base64.b64encode(buf.getvalue()).decode(), "OK"
    except Exception as e:
        return None, "ERROR:" + type(e).__name__

def read_as7341():
    try:
        import board, adafruit_as7341
    except Exception:
        return None, NC
    try:
        s = adafruit_as7341.AS7341(board.I2C())
        # Chamber LED: set s.led / s.led_current here according to your wiring (fixed illumination).
        d = {"F1_415nm": s.channel_415nm, "F2_445nm": s.channel_445nm, "F3_480nm": s.channel_480nm,
             "F4_515nm": s.channel_515nm, "F5_555nm": s.channel_555nm, "F6_590nm": s.channel_590nm,
             "F7_630nm": s.channel_630nm, "F8_680nm": s.channel_680nm, "clear": s.channel_clear, "nir": s.channel_nir}
        return d, "OK"
    except Exception as e:
        return None, "ERROR:" + type(e).__name__

def read_bme688():
    try:
        import bme680
    except Exception:
        return None, NC
    try:
        try:
            s = bme680.BME680(bme680.I2C_ADDR_PRIMARY)
        except Exception:
            s = bme680.BME680(bme680.I2C_ADDR_SECONDARY)
        s.set_humidity_oversample(bme680.OS_2X); s.set_temperature_oversample(bme680.OS_8X)
        s.set_filter(bme680.FILTER_SIZE_3); s.set_gas_status(bme680.ENABLE_GAS_MEAS)
        s.set_gas_heater_temperature(320); s.set_gas_heater_duration(150); s.select_gas_heater_profile(0)
        for _ in range(30):
            if s.get_sensor_data() and s.data.heat_stable:
                # single-profile gas resistance = VOC pattern input; NOT a pathogen detector
                return {"temperature_c": s.data.temperature, "humidity_percent": s.data.humidity,
                        "pressure_hpa": s.data.pressure, "gas_resistance_ohm": s.data.gas_resistance,
                        "heat_stable": True, "heater_c": 320, "heater_ms": 150}, "OK"
            time.sleep(0.2)
        return None, "ERROR:HeaterNotStable"
    except Exception as e:
        return None, "ERROR:" + type(e).__name__

def acquire():
    img, cs = read_camera(); sp, ss = read_as7341(); vo, vs = read_bme688()
    ok = [cs == "OK", ss == "OK", vs == "OK"]
    return {
        "service_version": "1.4", "data_source": "real",
        "acquisition_status": "COMPLETE" if all(ok) else ("PARTIAL" if any(ok) else "NO_REAL_SENSOR_DATA"),
        "sensor_status": {"camera": cs, "as7341": ss, "bme688": vs},
        "temperature_c": vo["temperature_c"] if vo else None,
        "humidity_percent": vo["humidity_percent"] if vo else None,
        "as7341": sp, "bme688": vo, "image_jpeg_base64": img,
        "acquired_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }

class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        if self.headers.get("X-MAED-Token", "") != TOKEN:
            return self._send(401, {"error": "unauthorized"})
        if self.path == "/v1/status":
            return self._send(200, {"service": "maed-pi", "version": "1.4"})
        if self.path == "/v1/acquire":
            return self._send(200, acquire())
        self._send(404, {"error": "not found"})

    def log_message(self, *a):
        pass

if __name__ == "__main__":
    if not TOKEN:
        sys.exit("Set MAED_TOKEN (shared secret) before starting.")
    port = int(os.environ.get("MAED_PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()
