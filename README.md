# SLW Off — dezactivează automat Speed Limit Warning pe BYD DiLink 3

Extras minim din [OverDrive](https://github.com/yash-srivastava/Overdrive-release) (MIT, © Yash Srivastava):
doar calea de scriere ADAS pentru SLW + bootstrap-ul de daemon prin ADB.

## Cum funcționează
1. Aplicația se conectează la ADB-ul local (`127.0.0.1:5555`, Wireless ADB) cu `dadb`.
2. Pornește `SlwDaemon` cu `app_process` ca uid shell (2000) — același truc ca OverDrive,
   pentru că SDK-ul BYD (`BYDAutoADASDevice`) cere permisiuni de sistem.
3. Daemon-ul ascultă power level-ul mașinii (`BYDAutoBodyworkDevice`, 0=OFF 1=ACC 2=ON 3=OK).
   La trecerea în **ON** (sau la pornirea tabletei cu mașina deja pornită) deschide o fereastră
   de 90 s: verifică SLW (`ADAS_SLW_FUNC_SWITCH_STATE`, 1=off/2=on) și, dacă e pornit, scrie 1 pe
   `ADAS_SLW_FUNC_SWITCH_STATE_SET` (fallback `ADAS_ISLA_SWITCH_SET`). După fereastră nu mai
   atinge SLW până la următoarea pornire — dacă îl activezi manual în mers, rămâne activ.
4. Repornire automată a daemon-ului: `BOOT_COMPLETED` + watchdog la 5 min.

## Instalare
1. Wireless ADB activ pe unitate (ca pentru OverDrive).
2. `adb install app-release.apk`, deschide **SLW Off** o dată → acceptă promptul
   „Allow USB debugging?” cu **Always allow**.
3. Statusul arată `Daemon: OK state=1 ...`. Butonul **Log** arată `/data/local/tmp/slwoff.log`.

**Oprește SLW acum**: deschide manual fereastra de 90 s (util la test).

## Build
Fără Android Studio: pune proiectul într-un repo GitHub — workflow-ul `.github/workflows/build.yml`
produce APK-ul ca artefact. Sau `gradle assembleRelease` local cu Android SDK.

## Test manual (fără APK), de pe laptop
```
adb shell "CLASSPATH=\$(pm path ro.vik.slwoff | sed 's/package://') app_process /system/bin ro.vik.slwoff.SlwDaemon"
```
