#!/usr/bin/env bash
# Retry detekt tasks that OOM'd during image build (needs Docker, ~18 min/task).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
log="$HOME/.jetpacker/overnight-certify.log"
harbor="${JETPACKER_HARBOR:-/tmp/kotlin-swe-bench/tasks}"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$log"; }

log "certify detekt (retries OOM / uncertified tasks)"
cd "$root"
./gradlew :eval:certify -q \
  -Pjetpacker.harbor="$harbor" \
  -Pjetpacker.harbor.repo=detekt >> "$log" 2>&1
log "certify detekt finished"
