#!/usr/bin/env bash
# Level-1 benchmarks while ktlint L2 runs. Skips ktlint (same clone as L2). Sequential to limit RAM.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
log="$HOME/.jetpacker/overnight-l1.log"
harbor="${JETPACKER_HARBOR:-/tmp/kotlin-swe-bench/tasks}"
budgets="${JETPACKER_BUDGETS:-1000,2000,4000,8000}"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$log"; }

clone() {
  local url=$1 dir=$2
  if [[ ! -d "/tmp/$dir/.git" ]]; then
    log "cloning $url -> /tmp/$dir"
    git clone --quiet "$url" "/tmp/$dir"
  fi
}

run_bench() {
  local repo=$1 harbor_repo=$2 tasks=$3 cache=$4 extra=("${@:5}")
  log "L1 start repo=$harbor_repo tasks=$tasks cache=$cache"
  cd "$root"
  ./gradlew :eval:run -q \
    -Pjetpacker.repo="/tmp/$repo" \
    -Pjetpacker.harbor="$harbor" \
    -Pjetpacker.harbor.repo="$harbor_repo" \
    -Pjetpacker.tasks="$tasks" \
    -Pjetpacker.budgets="$budgets" \
    -Pjetpacker.cache="$cache" \
    "${extra[@]}" >> "$log" 2>&1
  log "L1 done repo=$harbor_repo"
}

mkdir -p "$HOME/.jetpacker"
log "overnight L1 chain starting"

clone https://github.com/oss-review-toolkit/ort ort
clone https://github.com/Hannah-Sten/TeXiFy-IDEA TeXiFy
clone https://github.com/GradleUp/shadow shadow
clone https://github.com/Kotlin/dataframe dataframe-full

# Harbor issue suites (+imports/+references arms included in default retriever list).
run_bench detekt detekt 28 "$HOME/.jetpacker-detekt"
run_bench ort ort 12 "$HOME/.jetpacker-ort"
run_bench dataframe-full dataframe 5 "$HOME/.jetpacker-dataframe"
run_bench TeXiFy TeXiFy-IDEA 4 "$HOME/.jetpacker-texify"
run_bench shadow shadow 3 "$HOME/.jetpacker-shadow"

# Mined detekt commits (no harbor).
log "L1 start mined detekt commits"
cd "$root"
./gradlew :eval:run -q \
  -Pjetpacker.repo=/tmp/detekt \
  -Pjetpacker.tasks=60 \
  -Pjetpacker.budgets="$budgets" \
  -Pjetpacker.cache="$HOME/.jetpacker-detekt-mined" >> "$log" 2>&1
log "L1 done mined detekt"

log "overnight L1 chain finished"
