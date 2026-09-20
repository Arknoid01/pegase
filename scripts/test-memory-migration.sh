#!/usr/bin/env bash
# Valide la migration id/updatedAtMs sur une copie offline (pas la prod live).
# Chemins Drive : voir scripts/pegase-drive-sync.defaults.env
# Usage :
#   ./scripts/test-memory-migration.sh [chemin/permanent.json]
#
# Si aucun chemin : tire une copie depuis le tel via adb (run-as).

set -euo pipefail

WORKDIR="${PEGASE_MIGRATION_WORKDIR:-/tmp/pegase-migration-test}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

mkdir -p "${WORKDIR}"

if [[ $# -ge 1 ]]; then
  SRC="$1"
else
  if ! adb get-state >/dev/null 2>&1; then
    echo "ADB indisponible — passe un fichier : $0 /chemin/permanent.json" >&2
    exit 1
  fi
  adb shell run-as com.pegasuscorp.orbe cat files/memory/permanent.json \
    > "${WORKDIR}/permanent-with-ids.json"
  SRC="${WORKDIR}/permanent-with-ids.json"
fi

WITH_IDS="${WORKDIR}/permanent-with-ids.json"
PRE="${WORKDIR}/pre-migration.json"

if [[ "$(readlink -f "${SRC}")" != "$(readlink -f "${WITH_IDS}")" ]]; then
  cp "${SRC}" "${WITH_IDS}"
fi
jq 'map(del(.id, .updatedAtMs))' "${WITH_IDS}" > "${PRE}"

COUNT="$(jq 'length' "${PRE}")"
echo "[migration] ${COUNT} entrée(s) — fixture ${PRE}"

cd "${REPO}"
./gradlew :app:testDebugUnitTest \
  --tests "com.pegasuscorp.orbe.memory.MemoryMigrationTest" \
  -Dpegase.migration.fixture="${PRE}" \
  -Dpegase.migration.reference="${WITH_IDS}" \
  >/dev/null

echo "[migration] OK — aucune perte, ids uniques, ids stables, concordance référence"
