#!/usr/bin/env bash
###############################################################################
# docker-entrypoint.sh — Telegram Android Docker Build Entrypoint
#
# Copies source into an isolated build directory, runs the Gradle build for
# the requested variant, and copies artifacts back to the mounted output path.
#
# Arguments:
#   $1  Build target (default: standalone). One of:
#         standalone  — Standalone APK  (no Google Play / Firebase)
#         release     — Google Play release APK
#         huawei      — Huawei AppGallery release APK
#         bundle      — Google Play AAB bundle
#         bundle23    — Google Play AAB bundle (SDK 23+)
#         debug       — Debug APK
#         all         — Build every target above
###############################################################################
set -euo pipefail

SOURCE_DIR="/home/source"
BUILD_DIR="/home/build"
OUTPUT_DIR="/home/output"

# ── Utility Functions ────────────────────────────────────────────────────────
log()  { printf '\n\033[1;34m>>> %s\033[0m\n' "$*"; }
err()  { printf '\n\033[1;31m!!! %s\033[0m\n' "$*" >&2; }
ok()   { printf '\033[1;32m  ✔ %s\033[0m\n' "$*"; }
ts()   { date '+%Y-%m-%d %H:%M:%S'; }

die() { err "$@"; exit 1; }

# ── Prepare Build Tree ───────────────────────────────────────────────────────
prepare_source() {
    log "Preparing build tree … ($(ts))"
    rm -rf "${BUILD_DIR:?}"/*
    cp -a "${SOURCE_DIR}/." "${BUILD_DIR}/"

    # Generate local.properties so the Gradle plugin can find the SDK/NDK
    cat > "${BUILD_DIR}/local.properties" <<EOF
sdk.dir=${ANDROID_HOME}
ndk.dir=${ANDROID_NDK_HOME}
EOF
    ok "Source copied to ${BUILD_DIR}"
}

# ── Collect Artifacts ────────────────────────────────────────────────────────
collect_artifacts() {
    log "Collecting build artifacts → ${OUTPUT_DIR} … ($(ts))"
    mkdir -p "${OUTPUT_DIR}"

    # APKs
    find "${BUILD_DIR}" -path '*/outputs/apk/*' -name '*.apk' | while read -r f; do
        rel="${f#"${BUILD_DIR}"/}"
        mkdir -p "${OUTPUT_DIR}/$(dirname "${rel}")"
        cp -v "${f}" "${OUTPUT_DIR}/${rel}"
    done

    # AABs (bundles)
    find "${BUILD_DIR}" -path '*/outputs/bundle/*' -name '*.aab' | while read -r f; do
        rel="${f#"${BUILD_DIR}"/}"
        mkdir -p "${OUTPUT_DIR}/$(dirname "${rel}")"
        cp -v "${f}" "${OUTPUT_DIR}/${rel}"
    done

    # Native debug symbols
    find "${BUILD_DIR}" -path '*/outputs/native-debug-symbols/*' -name '*.zip' | while read -r f; do
        rel="${f#"${BUILD_DIR}"/}"
        mkdir -p "${OUTPUT_DIR}/$(dirname "${rel}")"
        cp -v "${f}" "${OUTPUT_DIR}/${rel}"
    done

    ok "Artifacts collected."
}

# ── Build Targets ────────────────────────────────────────────────────────────
build_standalone() {
    log "Building Standalone APK … ($(ts))"
    cd "${BUILD_DIR}"
    gradle :TMessagesProj_AppStandalone:assembleAfatStandalone
    ok "Standalone APK built."
}

build_release() {
    log "Building Google Play Release APK … ($(ts))"
    cd "${BUILD_DIR}"
    gradle :TMessagesProj_App:assembleAfatRelease
    ok "Release APK built."
}

build_huawei() {
    log "Building Huawei Release APK … ($(ts))"
    cd "${BUILD_DIR}"
    gradle :TMessagesProj_AppHuawei:assembleAfatRelease
    ok "Huawei APK built."
}

build_bundle() {
    log "Building Google Play AAB Bundle … ($(ts))"
    cd "${BUILD_DIR}"
    gradle :TMessagesProj_App:bundleBundleAfatRelease
    ok "Bundle (afat) built."
}

build_bundle23() {
    log "Building Google Play AAB Bundle (SDK 23+) … ($(ts))"
    cd "${BUILD_DIR}"
    gradle :TMessagesProj_App:bundleBundleAfat_SDK23Release
    ok "Bundle (SDK23) built."
}

build_debug() {
    log "Building Debug APK … ($(ts))"
    cd "${BUILD_DIR}"
    gradle :TMessagesProj_App:assembleAfatDebug
    ok "Debug APK built."
}

build_all() {
    build_bundle23
    build_bundle
    build_standalone
    build_release
    build_huawei
}

# ── Main ─────────────────────────────────────────────────────────────────────
TARGET="${1:-standalone}"

log "Telegram Android Docker Build  —  target: ${TARGET}  ($(ts))"
log "ANDROID_HOME  = ${ANDROID_HOME}"
log "ANDROID_NDK   = ${ANDROID_NDK_HOME}"
log "GRADLE_HOME   = ${GRADLE_USER_HOME}"

prepare_source

case "${TARGET}" in
    standalone) build_standalone ;;
    release)    build_release    ;;
    huawei)     build_huawei     ;;
    bundle)     build_bundle     ;;
    bundle23)   build_bundle23   ;;
    debug)      build_debug      ;;
    all)        build_all        ;;
    *)          die "Unknown build target: ${TARGET}. Use one of: standalone release huawei bundle bundle23 debug all" ;;
esac

collect_artifacts

log "Build complete!  ($(ts))"
log "Output: ${OUTPUT_DIR}"
ls -lhR "${OUTPUT_DIR}" 2>/dev/null || true
