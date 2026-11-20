# SLW Off

**Language:** [Română](#română) | [English](#english)

---

# Română

## SLW Off — dezactivează automat Speed Limit Warning pe BYD DiLink 3

Extras minim din [OverDrive](https://github.com/yash-srivastava/Overdrive-release) (MIT, © Yash Srivastava):
doar calea de scriere ADAS pentru SLW + bootstrap-ul de daemon prin ADB.

## Cum funcționează

1. Aplicația se conectează la ADB-ul local (`127.0.0.1:5555`, Wireless ADB) cu `dadb`.
2. Pornește `SlwDaemon` cu `app_process` ca uid shell (2000) — același truc ca OverDrive, deoarece SDK-ul BYD (`BYDAutoADASDevice`) cere permisiuni de sistem.
3. Daemon-ul ascultă nivelul de alimentare al mașinii (`BYDAutoBodyworkDevice`, 0=OFF, 1=ACC, 2=ON, 3=OK).
4. Repornire automată a daemon-ului prin `BOOT_COMPLETED` și watchdog la 5 minute.

## Instalare

1. Activează Wireless ADB pe unitatea BYD.
2. `adb install app-release.apk`
3. Deschide aplicația o dată și acceptă promptul ADB.

## Test manual

```bash
adb shell "CLASSPATH=\$(pm path ro.vik.slwoff | sed 's/package://') app_process /system/bin ro.vik.slwoff.SlwDaemon"
```

---

# English

## SLW Off — Automatically disables Speed Limit Warning on BYD DiLink 3

Minimal extraction from [OverDrive](https://github.com/yash-srivastava/Overdrive-release) (MIT, © Yash Srivastava).

## How it works

1. Connects to local Wireless ADB.
2. Starts `SlwDaemon` through `app_process` as shell uid.
3. Monitors vehicle power state and disables SLW during a 90-second startup window.
4. Automatically restarts on boot and via watchdog.

## Installation

1. Enable Wireless ADB.
2. `adb install app-release.apk`
3. Open the app once and accept the ADB authorization prompt.

## Manual test

```bash
adb shell "CLASSPATH=\$(pm path ro.vik.slwoff | sed 's/package://') app_process /system/bin ro.vik.slwoff.SlwDaemon"
```
