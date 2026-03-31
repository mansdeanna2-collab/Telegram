#!/usr/bin/env bash
###############################################################################
# setup-build-env.sh — Ubuntu 22.04 Server Environment Setup
#
# Installs Docker Engine, Docker Compose, and all prerequisites needed to
# build Telegram Android APKs in Docker containers.
#
# Requirements:
#   - Ubuntu 22.04 LTS (Jammy Jellyfish)
#   - Root or sudo access
#   - Internet connectivity
#
# Usage:
#   chmod +x scripts/setup-build-env.sh
#   sudo ./scripts/setup-build-env.sh
###############################################################################
set -euo pipefail

# ── Constants ────────────────────────────────────────────────────────────────
DOCKER_COMPOSE_VERSION="v2.27.0"
MIN_DISK_GB=30
MIN_RAM_MB=4096

# ── Colors ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()   { echo -e "${GREEN}[OK]${NC}    $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*" >&2; }
die()  { err "$@"; exit 1; }

# ── Pre-flight Checks ───────────────────────────────────────────────────────
preflight_checks() {
    log "Running pre-flight checks …"

    # Must be root
    if [[ $EUID -ne 0 ]]; then
        die "This script must be run as root (use sudo)."
    fi

    # Check Ubuntu version
    if [[ -f /etc/os-release ]]; then
        # shellcheck source=/dev/null
        source /etc/os-release
        if [[ "${ID}" != "ubuntu" ]]; then
            warn "Detected OS: ${ID}. This script is designed for Ubuntu 22.04."
        fi
        if [[ "${VERSION_ID}" != "22.04" ]]; then
            warn "Detected Ubuntu ${VERSION_ID}. This script targets 22.04 LTS."
        fi
    else
        warn "Cannot detect OS version. Proceeding anyway."
    fi

    # Check disk space
    local avail_gb
    avail_gb=$(df -BG / | awk 'NR==2{print $4}' | tr -d 'G')
    if (( avail_gb < MIN_DISK_GB )); then
        warn "Low disk space: ${avail_gb}GB available (recommend ≥${MIN_DISK_GB}GB)."
        warn "The Docker image + Gradle cache can consume ~20GB."
    else
        ok "Disk space: ${avail_gb}GB available."
    fi

    # Check RAM
    local total_ram_mb
    total_ram_mb=$(free -m | awk '/^Mem:/{print $2}')
    if (( total_ram_mb < MIN_RAM_MB )); then
        warn "Low RAM: ${total_ram_mb}MB available (recommend ≥${MIN_RAM_MB}MB)."
    else
        ok "RAM: ${total_ram_mb}MB available."
    fi

    ok "Pre-flight checks complete."
}

# ── Install System Dependencies ──────────────────────────────────────────────
install_system_deps() {
    log "Installing system dependencies …"
    apt-get update -qq
    apt-get install -y --no-install-recommends \
        apt-transport-https \
        ca-certificates \
        curl \
        gnupg \
        lsb-release \
        git \
        python3 \
        python3-pip \
        unzip \
        software-properties-common
    ok "System dependencies installed."
}

# ── Install Docker Engine ────────────────────────────────────────────────────
install_docker() {
    if command -v docker &>/dev/null; then
        local ver
        ver=$(docker --version)
        ok "Docker already installed: ${ver}"
        return 0
    fi

    log "Installing Docker Engine …"

    # Add Docker GPG key
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc

    # Add Docker repository
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
       https://download.docker.com/linux/ubuntu \
       $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" \
      > /etc/apt/sources.list.d/docker.list

    apt-get update -qq
    apt-get install -y --no-install-recommends \
        docker-ce \
        docker-ce-cli \
        containerd.io \
        docker-buildx-plugin \
        docker-compose-plugin

    # Enable and start Docker
    systemctl enable docker
    systemctl start docker

    ok "Docker Engine installed: $(docker --version)"
}

# ── Configure Docker for Non-root ────────────────────────────────────────────
configure_docker_user() {
    local target_user="${SUDO_USER:-}"
    if [[ -z "${target_user}" ]]; then
        warn "No SUDO_USER detected. Skipping non-root Docker configuration."
        return 0
    fi

    log "Adding user '${target_user}' to the docker group …"
    usermod -aG docker "${target_user}"
    ok "User '${target_user}' added to docker group."
    warn "You must log out and back in for this to take effect."
}

# ── Install Docker Compose (standalone binary, if plugin not available) ──────
ensure_docker_compose() {
    if docker compose version &>/dev/null; then
        ok "Docker Compose (plugin) available: $(docker compose version)"
        return 0
    fi

    log "Installing Docker Compose standalone …"
    curl -fsSL \
        "https://github.com/docker/compose/releases/download/${DOCKER_COMPOSE_VERSION}/docker-compose-$(uname -s)-$(uname -m)" \
        -o /usr/local/bin/docker-compose
    chmod +x /usr/local/bin/docker-compose
    ok "Docker Compose installed: $(/usr/local/bin/docker-compose --version)"
}

# ── Configure System for Build ───────────────────────────────────────────────
configure_system() {
    log "Tuning system settings for Android builds …"

    # Increase inotify watches (Gradle file watchers)
    if ! grep -q 'fs.inotify.max_user_watches' /etc/sysctl.conf 2>/dev/null; then
        echo 'fs.inotify.max_user_watches=524288' >> /etc/sysctl.conf
        sysctl -p >/dev/null 2>&1 || true
    fi

    # Increase max memory map areas (Gradle / JVM)
    if ! grep -q 'vm.max_map_count' /etc/sysctl.conf 2>/dev/null; then
        echo 'vm.max_map_count=262144' >> /etc/sysctl.conf
        sysctl -p >/dev/null 2>&1 || true
    fi

    ok "System settings tuned."
}

# ── Build Docker Image ───────────────────────────────────────────────────────
build_docker_image() {
    local project_dir="${1:-}"
    if [[ -z "${project_dir}" || ! -f "${project_dir}/docker/Dockerfile.build" ]]; then
        warn "Project directory not provided or Dockerfile.build not found."
        warn "You can build it later with:"
        echo "  cd /path/to/Telegram"
        echo "  docker build -f docker/Dockerfile.build -t telegram-builder ."
        return 0
    fi

    log "Building Docker image (telegram-builder) — this may take 10-20 minutes …"
    cd "${project_dir}"
    docker build -f docker/Dockerfile.build -t telegram-builder .
    ok "Docker image 'telegram-builder' built successfully."
}

# ── Print Summary ────────────────────────────────────────────────────────────
print_summary() {
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║          Telegram APK Build Environment — Ready!               ║"
    echo "╠══════════════════════════════════════════════════════════════════╣"
    echo "║                                                                ║"
    echo "║  Docker:  $(docker --version | head -c 50)$(printf '%*s' $((16 - $(docker --version | head -c 50 | wc -c))) '')║"
    echo "║                                                                ║"
    echo "║  Next steps:                                                   ║"
    echo "║                                                                ║"
    echo "║  1. Log out and back in (for docker group):                    ║"
    echo "║       newgrp docker                                            ║"
    echo "║                                                                ║"
    echo "║  2. Build the Docker image:                                    ║"
    echo "║       cd /path/to/Telegram                                     ║"
    echo "║       docker build -f docker/Dockerfile.build \\               ║"
    echo "║           -t telegram-builder .                                ║"
    echo "║                                                                ║"
    echo "║  3. Build an APK:                                              ║"
    echo "║       ./scripts/build-apk.sh standalone                        ║"
    echo "║       ./scripts/build-apk.sh release                           ║"
    echo "║       ./scripts/build-apk.sh all                               ║"
    echo "║                                                                ║"
    echo "║  Or use Docker Compose:                                        ║"
    echo "║       docker compose -f docker/docker-compose.build.yml \\     ║"
    echo "║           run --rm build-standalone                            ║"
    echo "║                                                                ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
}

# ── Main ─────────────────────────────────────────────────────────────────────
main() {
    echo ""
    log "═══════════════════════════════════════════════════════════════"
    log " Telegram Android APK — Build Environment Setup (Ubuntu 22.04)"
    log "═══════════════════════════════════════════════════════════════"
    echo ""

    preflight_checks
    install_system_deps
    install_docker
    configure_docker_user
    ensure_docker_compose
    configure_system

    # Optionally build the Docker image if we're inside the project
    local script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    local project_dir
    project_dir="$(dirname "${script_dir}")"
    build_docker_image "${project_dir}"

    print_summary
}

main "$@"
