#!/usr/bin/env bash
###############################################################################
# build-apk.sh — Telegram Android APK Docker Build Script
#
# Builds Telegram Android APKs inside a Docker container with all required
# tools pre-installed (Android SDK 35, NDK 21.4, Gradle 8.7, JDK 17).
#
# Prerequisites:
#   - Docker Engine installed (run scripts/setup-build-env.sh first)
#   - Docker image built:
#       docker build -f docker/Dockerfile.build -t telegram-builder .
#
# Usage:
#   ./scripts/build-apk.sh [OPTIONS] <target>
#
# Targets:
#   standalone  — Standalone APK (no Google Play services / Firebase)
#   release     — Google Play release APK (universal, all ABIs)
#   huawei      — Huawei AppGallery release APK
#   bundle      — Google Play AAB bundle (all ABIs)
#   bundle23    — Google Play AAB bundle (SDK 23+)
#   debug       — Debug APK
#   all         — Build all targets above
#
# Options:
#   -o, --output DIR    Output directory (default: ./build-output)
#   -t, --tag TAG       Docker image tag (default: telegram-builder)
#   -j, --jobs N        Max parallel Gradle workers (default: auto)
#   -m, --memory SIZE   JVM max heap (default: 4096m)
#   --no-cache          Disable Gradle cache volume
#   --rebuild-image     Force rebuild the Docker image before building
#   -h, --help          Show this help message
#
# Examples:
#   ./scripts/build-apk.sh standalone
#   ./scripts/build-apk.sh -o /tmp/apk-output release
#   ./scripts/build-apk.sh --jobs 2 --memory 8192m all
#   ./scripts/build-apk.sh --rebuild-image release
###############################################################################
set -euo pipefail

# ── Resolve Paths ────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ── Defaults ─────────────────────────────────────────────────────────────────
DOCKER_IMAGE="telegram-builder"
OUTPUT_DIR="${PROJECT_DIR}/build-output"
GRADLE_WORKERS=""
JVM_HEAP="4096m"
USE_CACHE=true
REBUILD_IMAGE=false
BUILD_TARGET=""

# ── Colors ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $*"; }
ok()   { echo -e "${GREEN}[$(date '+%H:%M:%S')] ✔${NC} $*"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] ⚠${NC} $*"; }
err()  { echo -e "${RED}[$(date '+%H:%M:%S')] ✘${NC} $*" >&2; }
die()  { err "$@"; exit 1; }

# ── Help ─────────────────────────────────────────────────────────────────────
usage() {
    cat <<'HELP'
Telegram Android APK — Docker Build Script

Usage: ./scripts/build-apk.sh [OPTIONS] <target>

Targets:
  standalone  — Standalone APK (no Google Play services / Firebase)
  release     — Google Play release APK (universal, all ABIs)
  huawei      — Huawei AppGallery release APK
  bundle      — Google Play AAB bundle (all ABIs)
  bundle23    — Google Play AAB bundle (SDK 23+)
  debug       — Debug APK
  all         — Build all targets above

Options:
  -o, --output DIR    Output directory (default: ./build-output)
  -t, --tag TAG       Docker image tag (default: telegram-builder)
  -j, --jobs N        Max parallel Gradle workers (default: auto)
  -m, --memory SIZE   JVM max heap (default: 4096m)
  --no-cache          Disable Gradle cache volume
  --rebuild-image     Force rebuild the Docker image before building
  -h, --help          Show this help message

Examples:
  ./scripts/build-apk.sh standalone
  ./scripts/build-apk.sh -o /tmp/apk-output release
  ./scripts/build-apk.sh --jobs 2 --memory 8192m all
  ./scripts/build-apk.sh --rebuild-image release
HELP
    exit 0
}

# ── Parse Arguments ──────────────────────────────────────────────────────────
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -o|--output)       OUTPUT_DIR="$2"; shift 2 ;;
            -t|--tag)          DOCKER_IMAGE="$2"; shift 2 ;;
            -j|--jobs)         GRADLE_WORKERS="$2"; shift 2 ;;
            -m|--memory)       JVM_HEAP="$2"; shift 2 ;;
            --no-cache)        USE_CACHE=false; shift ;;
            --rebuild-image)   REBUILD_IMAGE=true; shift ;;
            -h|--help)         usage ;;
            -*)                die "Unknown option: $1" ;;
            *)                 BUILD_TARGET="$1"; shift ;;
        esac
    done

    if [[ -z "${BUILD_TARGET}" ]]; then
        err "No build target specified."
        echo ""
        usage
    fi

    # Validate target
    case "${BUILD_TARGET}" in
        standalone|release|huawei|bundle|bundle23|debug|all) ;;
        *) die "Invalid target '${BUILD_TARGET}'. Use one of: standalone release huawei bundle bundle23 debug all" ;;
    esac
}

# ── Check Docker ─────────────────────────────────────────────────────────────
check_docker() {
    if ! command -v docker &>/dev/null; then
        die "Docker is not installed. Run: sudo ./scripts/setup-build-env.sh"
    fi

    if ! docker info &>/dev/null; then
        die "Docker daemon is not running, or you don't have permission. Try: sudo systemctl start docker"
    fi

    ok "Docker is available."
}

# ── Build or Check Docker Image ──────────────────────────────────────────────
ensure_docker_image() {
    if [[ "${REBUILD_IMAGE}" == true ]]; then
        log "Rebuilding Docker image '${DOCKER_IMAGE}' …"
        docker build -f "${PROJECT_DIR}/docker/Dockerfile.build" \
            -t "${DOCKER_IMAGE}" "${PROJECT_DIR}"
        ok "Docker image rebuilt."
        return 0
    fi

    if ! docker image inspect "${DOCKER_IMAGE}" &>/dev/null; then
        log "Docker image '${DOCKER_IMAGE}' not found. Building …"
        log "This may take 10-20 minutes (downloading Android SDK, NDK, etc.)"
        docker build -f "${PROJECT_DIR}/docker/Dockerfile.build" \
            -t "${DOCKER_IMAGE}" "${PROJECT_DIR}"
        ok "Docker image built."
    else
        ok "Docker image '${DOCKER_IMAGE}' found."
    fi
}

# ── Run Build ────────────────────────────────────────────────────────────────
run_build() {
    local start_time
    start_time=$(date +%s)

    mkdir -p "${OUTPUT_DIR}"

    # Build GRADLE_OPTS
    local gradle_opts="-Dorg.gradle.daemon=false -Xmx${JVM_HEAP}"
    if [[ -n "${GRADLE_WORKERS}" ]]; then
        gradle_opts="${gradle_opts} -Dorg.gradle.workers.max=${GRADLE_WORKERS}"
    fi

    # Docker volume mounts
    local -a docker_args=(
        --rm
        -v "${PROJECT_DIR}:/home/source:ro"
        -v "${OUTPUT_DIR}:/home/output"
        -e "GRADLE_OPTS=${gradle_opts}"
    )

    # Gradle cache volume
    if [[ "${USE_CACHE}" == true ]]; then
        docker_args+=( -v "telegram-gradle-cache:/home/gradle-cache" )
    fi

    echo ""
    log "╔══════════════════════════════════════════════════════════╗"
    log "║        Telegram Android APK — Docker Build              ║"
    log "╠══════════════════════════════════════════════════════════╣"
    log "║  Target:    ${BUILD_TARGET}"
    log "║  Image:     ${DOCKER_IMAGE}"
    log "║  Output:    ${OUTPUT_DIR}"
    log "║  JVM Heap:  ${JVM_HEAP}"
    log "║  Workers:   ${GRADLE_WORKERS:-auto}"
    log "║  Cache:     ${USE_CACHE}"
    log "╚══════════════════════════════════════════════════════════╝"
    echo ""

    log "Starting Docker build container …"
    docker run "${docker_args[@]}" "${DOCKER_IMAGE}" "${BUILD_TARGET}"

    local end_time
    end_time=$(date +%s)
    local duration=$(( end_time - start_time ))
    local minutes=$(( duration / 60 ))
    local seconds=$(( duration % 60 ))

    echo ""
    ok "Build completed in ${minutes}m ${seconds}s"
    echo ""
    log "Artifacts:"
    find "${OUTPUT_DIR}" -type f \( -name '*.apk' -o -name '*.aab' \) 2>/dev/null | while read -r f; do
        local size
        size=$(du -h "${f}" | cut -f1)
        echo -e "  ${GREEN}●${NC} ${f}  (${size})"
    done
    echo ""
}

# ── Main ─────────────────────────────────────────────────────────────────────
main() {
    parse_args "$@"
    check_docker
    ensure_docker_image
    run_build
}

main "$@"
