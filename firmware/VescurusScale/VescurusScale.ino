#include <HX711_ADC.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>

#if __has_include("ScaleWifi.h")
#include "ScaleWifi.h"
#else
#include "ScaleWifi.example.h"
#endif

HX711_ADC loadCell(18, 19);
LiquidCrystal_I2C lcd(0x27, 16, 2);
WebServer server(80);
Preferences settings;

struct Calibration {
  float factor;
  uint32_t revision;
};
Calibration calibration{350.0f, 0};
bool storageReady = false;
String deviceId, bootId, tareCommand;
uint32_t sequence = 0, sampleTime = 0, tareEpoch = 0, tareStarted = 0;
uint32_t stableSince = 0, lastDisplay = 0;
float grams = 0, low = 0, high = 0;
bool zeroed = false, taring = false, stable = false;
bool fresh() { return sequence && millis() - sampleTime <= 1000; }
bool ready() { return fresh() && zeroed && !taring; }

void startTare() {
  zeroed = false;
  taring = true;
  stable = false;
  tareStarted = millis();
  ++tareEpoch;
  loadCell.tareNoDelay();
}

void weight() {
  char body[480];
  snprintf(body, sizeof(body),
    "{\"deviceId\":\"%s\",\"bootId\":\"%s\",\"sequence\":%lu,"
    "\"senderUptimeMillis\":%lu,\"sampleAgeMillis\":%lu,\"grams\":%.3f,"
    "\"calibrationEpoch\":%lu,\"tareEpoch\":%lu,\"calibrated\":%s,"
    "\"stable\":%s,\"status\":\"%s\"}",
    deviceId.c_str(), bootId.c_str(), (unsigned long)sequence, (unsigned long)sampleTime,
    (unsigned long)(millis() - sampleTime), grams, (unsigned long)calibration.revision,
    (unsigned long)tareEpoch, calibration.revision ? "true" : "false",
    ready() && stable ? "true" : "false",
    !fresh() ? "sensor_unavailable" : taring ? "taring" : !zeroed ? "tare_required" : "ready");
  server.sendHeader("Cache-Control", "no-store");
  server.send(200, "application/json", body);
}

void lcdRow(uint8_t row, const char* text) {
  static char previous[2][17]{};
  char line[17];
  snprintf(line, sizeof(line), "%-16.16s", text);
  if (strcmp(previous[row], line) == 0) return;
  lcd.setCursor(0, row);
  lcd.print(line);
  memcpy(previous[row], line, sizeof(line));
}

void serialCommand(const String& command) {
  if (command == "tare" && fresh() && !taring) {
    tareCommand = "";
    startTare();
    Serial.println("Taring. Leave the empty holder still.");
    return;
  }
  if (!command.startsWith("cal ")) return;
  char* end = nullptr;
  const char* massText = command.c_str() + 4;
  const float known = strtof(massText, &end);
  if (!storageReady || end == massText || *end || !isfinite(known) ||
      known < 10 || known > 1000 || !ready() || !stable || fabsf(grams) < 5) {
    Serial.println("Tare, then place a stable known mass (10–1000 g) before calibration.");
    return;
  }
  const float factor = calibration.factor * grams / known;
  if (!isfinite(factor) || fabsf(factor) < 0.001f) return;
  Calibration next{factor, calibration.revision + 1};
  if (!next.revision || settings.putBytes("calibration", &next, sizeof(next)) != sizeof(next)) {
    Serial.println("Calibration could not be saved.");
    return;
  }
  calibration = next;
  loadCell.setCalFactor(calibration.factor);
  zeroed = false;
  stable = false;
  Serial.println("Calibration factor saved. Remove the known mass and tare again.");
}

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);
  lcd.init();
  lcd.backlight();
  lcdRow(0, "Starting scale");
  storageReady = settings.begin("vescurus-scale", false);
  Calibration saved{};
  if (storageReady && settings.getBytesLength("calibration") == sizeof(saved) &&
      settings.getBytes("calibration", &saved, sizeof(saved)) == sizeof(saved) &&
      isfinite(saved.factor) && fabsf(saved.factor) >= 0.001f && saved.revision) calibration = saved;
  loadCell.begin();
  loadCell.setCalFactor(calibration.factor);
  deviceId = String((uint32_t)(ESP.getEfuseMac() >> 32), HEX) + "-" + String((uint32_t)ESP.getEfuseMac(), HEX);
  bootId = String(esp_random(), HEX) + "-" + String(esp_random(), HEX);
  if (SCALE_WIFI_SSID[0]) {
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(SCALE_WIFI_SSID, SCALE_WIFI_PASSWORD);
  }
  server.on("/weight", HTTP_GET, weight);
  server.on("/tare", HTTP_POST, [] {
    const String id = server.arg("id");
    if (!id.length() || id.length() > 64) { server.send(400); return; }
    if (id == tareCommand) {
      server.send(taring ? 202 : zeroed ? 200 : 409);
      return;
    }
    if (!fresh() || taring) { server.send(409); return; }
    tareCommand = id;
    startTare();
    server.send(202);
  });
  server.begin();
  Serial.println("Empty the holder and type tare. To calibrate, use cal <known grams> after taring.");
}

void loop() {
  const uint32_t now = millis();
  if (loadCell.update()) {
    const float next = loadCell.getData();
    if (isfinite(next)) {
      if (!fresh() || next < high - 1.0f || next > low + 1.0f) {
        low = high = next;
        stableSince = now;
      }
      low = fminf(low, next);
      high = fmaxf(high, next);
      stable = now - stableSince >= 1000;
      grams = next;
      sampleTime = now;
      ++sequence;
    }
  }
  if (loadCell.getTareStatus() && taring) {
    taring = false;
    zeroed = true;
    stable = false;
    low = high = grams;
    stableSince = now;
    Serial.println("Tare complete.");
  }
  if (taring && now - tareStarted > 6000) {
    taring = false;
    zeroed = false;
  }
  if (sequence && !fresh()) { zeroed = false; stable = false; }
  static String input;
  while (Serial.available()) {
    const char c = Serial.read();
    if (c == '\n') {
      input.trim();
      serialCommand(input);
      input = "";
    } else if (input.length() < 64) input += c;
  }
  if (WiFi.status() == WL_CONNECTED) {
    static IPAddress shown;
    const IPAddress ip = WiFi.localIP();
    if (ip != shown) { shown = ip; Serial.print("Scale IP: "); Serial.println(ip); }
    server.handleClient();
  }
  const uint32_t displayNow = millis();
  if (displayNow - lastDisplay >= 250) {
    lastDisplay = displayNow;
    // Show the hotspot-assigned address periodically so it can be entered on Cook.
    const bool showAddress = !taring && WiFi.status() == WL_CONNECTED &&
      displayNow % 8000 < 2500;
    if (showAddress) {
      const String ip = WiFi.localIP().toString();
      lcdRow(0, ip.c_str());
    } else {
      lcdRow(0, !fresh() ? "Check sensor" : taring ? "Taring..." : !zeroed ? "Empty then tare" :
        !calibration.revision ? "Provisional" : stable ? "Weight" : "Settling");
    }
    char line[17] = "             --g";
    if (ready()) {
      // Suppress negative zero only in the rounded LCD presentation.
      const float displayGrams = fabsf(grams) < 0.05f ? 0.0f : grams;
      const int length = snprintf(line, sizeof(line), "%15.1fg", displayGrams);
      if (length != 16) memcpy(line, "      Overrangeg", sizeof(line));
    }
    lcdRow(1, line);
  }
}
