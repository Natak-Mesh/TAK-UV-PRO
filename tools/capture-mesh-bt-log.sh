#!/usr/bin/env bash
# Capture UV-PRO / MeshCore Bluetooth pairing and connect logs to the desktop.
# Use when diagnosing mesh pairing failures (e.g. Samsung S9 + node GPS quirk).
#
# Usage:
#   ./tools/capture-mesh-bt-log.sh
#   ./tools/capture-mesh-bt-log.sh SERIAL
#   DEVICE_SERIAL=R58M... ./tools/capture-mesh-bt-log.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Desktop output (Linux / macOS)
if [[ -d "${HOME}/Desktop" ]]; then
  OUT_DIR="${HOME}/Desktop"
elif [[ -d "${HOME}/desktop" ]]; then
  OUT_DIR="${HOME}/desktop"
else
  OUT_DIR="${REPO_ROOT}/logs"
fi

SERIAL="${1:-${DEVICE_SERIAL:-}}"
ADB=(adb)
if [[ -n "${SERIAL}" ]]; then
  ADB=(adb -s "${SERIAL}")
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found. Install Android platform-tools and ensure adb is on PATH."
  exit 1
fi

DEVICE_COUNT="$("${ADB[@]}" devices 2>/dev/null | awk 'NR>1 && $2=="device" {c++} END {print c+0}')"
if [[ "${DEVICE_COUNT}" -eq 0 ]]; then
  echo "ERROR: No Android device detected. Enable USB debugging and accept the RSA prompt."
  "${ADB[@]}" devices -l || true
  exit 1
fi

if [[ -z "${SERIAL}" && "${DEVICE_COUNT}" -gt 1 ]]; then
  echo "Multiple devices connected — pick one:"
  "${ADB[@]}" devices -l
  echo
  read -r -p "Enter device serial: " SERIAL
  if [[ -z "${SERIAL}" ]]; then
    echo "ERROR: Serial required when more than one device is connected."
    exit 1
  fi
  ADB=(adb -s "${SERIAL}")
fi

if [[ -z "${SERIAL}" ]]; then
  SERIAL="$("${ADB[@]}" get-serialno 2>/dev/null | tr -d '\r')"
fi

STAMP="$(date +%Y%m%d_%H%M%S)"
MODEL="$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | tr ' ' '_')"
ANDROID="$("${ADB[@]}" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
LOG_FILE="${OUT_DIR}/uvpro_mesh_bt_${STAMP}_${SERIAL}_${MODEL}.log"
SUMMARY_FILE="${OUT_DIR}/uvpro_mesh_bt_${STAMP}_${SERIAL}_${MODEL}_summary.txt"
POINTER="${REPO_ROOT}/logs/.active_mesh_bt_capture"

mkdir -p "${OUT_DIR}" "${REPO_ROOT}/logs"
echo "${LOG_FILE}" > "${POINTER}"

echo "=============================================="
echo " UV-PRO Mesh / Bluetooth log capture"
echo "=============================================="
echo " Device : ${SERIAL} (${MODEL}, Android ${ANDROID})"
echo " Output : ${LOG_FILE}"
echo
echo " Steps:"
echo "   1. Leave this terminal open."
echo "   2. On the phone: open ATAK, load UV-PRO plugin."
echo "   3. Reproduce the issue (pair/connect mesh — try WITH and WITHOUT node GPS)."
echo "   4. When finished, return here and press ENTER to stop capture."
echo
read -r -p "Press ENTER to START log capture... " _

{
  echo "# UV-PRO Mesh Bluetooth capture"
  echo "# started: $(date -Is)"
  echo "# device: ${SERIAL}"
  echo "# model: ${MODEL}"
  echo "# android: ${ANDROID}"
  echo "# package: com.atakmap.app.civ / com.uvpro.plugin"
  echo "#"
} >> "${LOG_FILE}"

"${ADB[@]}" logcat -c

# Broad enough for Samsung BT stack + UV-PRO plugin + pairing + BLE GATT + location (scan/pair quirks)
LOG_TAGS=(
  UVPro:V UVPro.BT:V UVPro.MeshBLE:V UVPro.MeshBLEProbe:V UVPro.UI:V
  BluetoothAdapter:V BluetoothDevice:V BluetoothGatt:V BluetoothGattCallback:V
  BluetoothPairingRequest:V BTPairingController:V BluetoothBondStateMachine:V
  bt_btif_dm:V bt_btm_sec:V bt_bta_gattc:V smp:V
  ActivityTaskManager:I
  LocationManagerService:I FusedLocation:I GnssLocationProvider:I
)

echo "Capturing... (reproduce the pairing/connect issue on the phone now)"
"${ADB[@]}" logcat -v threadtime "${LOG_TAGS[@]}" >> "${LOG_FILE}" &
LOG_PID=$!

cleanup() {
  if kill -0 "${LOG_PID}" 2>/dev/null; then
    kill "${LOG_PID}" 2>/dev/null || true
    wait "${LOG_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

read -r -p "Press ENTER to STOP capture... " _

cleanup
trap - EXIT INT TERM

{
  echo "# ended: $(date -Is)"
} >> "${LOG_FILE}"

# Quick summary for email / ticket attachment
{
  echo "UV-PRO Mesh BT capture summary"
  echo "Device: ${SERIAL} ${MODEL} Android ${ANDROID}"
  echo "Log: ${LOG_FILE}"
  echo "Ended: $(date -Is)"
  echo
  echo "--- Pairing / bond ---"
  grep -E "createBond|PAIRING_REQUEST|pairingVariant|bond_state|BOND_|BluetoothPairing|BTPairing|SMP_|smp_" "${LOG_FILE}" 2>/dev/null | tail -80 || true
  echo
  echo "--- UV-PRO plugin (mesh) ---"
  grep -E "UVPro\.(MeshBLE|MeshBLEProbe|UI|BT)" "${LOG_FILE}" 2>/dev/null | tail -120 || true
  echo
  echo "--- GATT connect / disconnect ---"
  grep -E "GATT|gattc|connectInternal|Boot auto-connect|Post-radio|Pairing|probe|avail=" "${LOG_FILE}" 2>/dev/null | tail -80 || true
  echo
  echo "--- Location / GPS (phone + mesh node hints) ---"
  grep -Ei "gps|location|ACCESS_FINE|ACCESS_COARSE|SETTING text" "${LOG_FILE}" 2>/dev/null | tail -40 || true
} > "${SUMMARY_FILE}"

echo
echo "Done."
echo "  Full log : ${LOG_FILE}"
echo "  Summary  : ${SUMMARY_FILE}"
echo "  Pointer  : ${POINTER}"
wc -l "${LOG_FILE}" 2>/dev/null || true
