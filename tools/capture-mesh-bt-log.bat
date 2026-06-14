@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM Capture UV-PRO / MeshCore Bluetooth logs to the Windows desktop.
REM Usage: double-click or run from cmd:
REM   tools\capture-mesh-bt-log.bat
REM   tools\capture-mesh-bt-log.bat SERIAL

set "SERIAL=%~1"
if not defined SERIAL if defined DEVICE_SERIAL set "SERIAL=%DEVICE_SERIAL%"

where adb >nul 2>&1
if errorlevel 1 (
  echo ERROR: adb not found. Install Android platform-tools and add adb to PATH.
  exit /b 1
)

if not defined SERIAL (
  for /f "skip=1 tokens=1,2" %%A in ('adb devices 2^>nul') do (
    if "%%B"=="device" (
      set "SERIAL=%%A"
      goto :have_serial
    )
  )
)
:have_serial

if not defined SERIAL (
  echo ERROR: No device found. Enable USB debugging and accept the RSA prompt.
  adb devices -l
  exit /b 1
)

set "OUT_DIR=%USERPROFILE%\Desktop"
if not exist "%OUT_DIR%" set "OUT_DIR=%USERPROFILE%"

for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "STAMP=%%T"
for /f %%M in ('adb -s %SERIAL% shell getprop ro.product.model') do set "MODEL=%%M"
for /f %%A in ('adb -s %SERIAL% shell getprop ro.build.version.release') do set "ANDROID=%%A"
set "MODEL=%MODEL: =_%"

set "LOG_FILE=%OUT_DIR%\uvpro_mesh_bt_%STAMP%_%SERIAL%_%MODEL%.log"

echo ==============================================
echo  UV-PRO Mesh / Bluetooth log capture
echo ==============================================
echo  Device : %SERIAL% (%MODEL%, Android %ANDROID%)
echo  Output : %LOG_FILE%
echo.
echo  Steps:
echo    1. Leave this window open.
echo    2. On the phone: open ATAK, load UV-PRO plugin.
echo    3. Reproduce pairing/connect (try WITH and WITHOUT node GPS on).
echo    4. Press any key here when finished.
echo.
pause

echo # UV-PRO Mesh Bluetooth capture > "%LOG_FILE%"
echo # device: %SERIAL% >> "%LOG_FILE%"
echo # model: %MODEL% >> "%LOG_FILE%"

adb -s %SERIAL% logcat -c
echo Capturing... reproduce the issue on the phone now.
start /b cmd /c "adb -s %SERIAL% logcat -v threadtime UVPro:V UVPro.BT:V UVPro.MeshBLE:V UVPro.MeshBLEProbe:V UVPro.UI:V BluetoothAdapter:V BluetoothDevice:V BluetoothGatt:V BluetoothPairingRequest:V BTPairingController:V bt_btif_dm:V bt_btm_sec:V bt_bta_gattc:V smp:V ActivityTaskManager:I LocationManagerService:I FusedLocation:I GnssLocationProvider:I >> \"%LOG_FILE%\""

pause

echo Done. Log saved to:
echo   %LOG_FILE%
endlocal
