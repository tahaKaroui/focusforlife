#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "run as root: sudo ./scripts/install_boot_services.sh" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_USER="${SUDO_USER:-root}"
BUILD_HOME="$(getent passwd "${BUILD_USER}" | cut -d: -f6)"
ASSET_DIR="/tmp/ffl-install-assets"

# Find cargo — rustup installs it in ~/.cargo/bin
CARGO="${BUILD_HOME}/.cargo/bin/cargo"
if [[ ! -x "${CARGO}" ]]; then
  CARGO="$(su -l "${BUILD_USER}" -c 'which cargo' 2>/dev/null || true)"
fi
if [[ -z "${CARGO}" || ! -x "${CARGO}" ]]; then
  echo "ERROR: cargo not found. Install Rust via https://rustup.rs" >&2
  exit 1
fi

apt-get update
apt-get install -y unbound

# Build all binaries as the normal user (not root).
su -l "${BUILD_USER}" -c "${CARGO} build --release --workspace --manifest-path ${REPO_ROOT}/Cargo.toml"

"${REPO_ROOT}/target/release/ffl-daemon" \
  --write-dns-test-assets "${ASSET_DIR}" \
  --unbound-blocklist-include-path /etc/unbound/focusforlife/focusforlife-blocklist.conf

# Install config (never overwrites existing user config).
install -d /etc/focusforlife
install -d /etc/unbound/focusforlife
install -d /var/lib/focusforlife
if [[ ! -f /etc/focusforlife/config.toml ]]; then
  install -m 644 "${REPO_ROOT}/config/example.toml" /etc/focusforlife/config.toml
fi
if [[ ! -f /etc/focusforlife/blocked-domains.txt ]]; then
  install -m 644 "${REPO_ROOT}/config/blocked-domains.txt" /etc/focusforlife/blocked-domains.txt
fi

# Install DNS assets.
install -m 644 "${ASSET_DIR}/unbound.conf"                /etc/unbound/focusforlife/unbound.conf
install -m 644 "${ASSET_DIR}/focusforlife-blocklist.conf" /etc/unbound/focusforlife/focusforlife-blocklist.conf
install -m 644 "${ASSET_DIR}/ffl-dns.nft"                 /etc/unbound/focusforlife/ffl-dns.nft

# Install binaries.
install -m 755 "${REPO_ROOT}/target/release/ffl-daemon"  /usr/local/bin/ffl-daemon
install -m 755 "${REPO_ROOT}/target/release/ffl-watcher" /usr/local/bin/ffl-watcher
install -m 755 "${REPO_ROOT}/target/release/ffl-ui"      /usr/local/bin/ffl-ui

# Install and enable system services.
install -m 644 "${REPO_ROOT}/systemd/ffl-resolver.service" /etc/systemd/system/ffl-resolver.service
install -m 644 "${REPO_ROOT}/systemd/ffl-firewall.service" /etc/systemd/system/ffl-firewall.service
install -m 644 "${REPO_ROOT}/systemd/ffl-daemon.service"   /etc/systemd/system/ffl-daemon.service
install -m 644 "${REPO_ROOT}/systemd/ffl-watcher.service"  /etc/systemd/system/ffl-watcher.service

systemctl daemon-reload
nft delete table inet focusforlife_dns 2>/dev/null || true
systemctl enable --now ffl-resolver.service
systemctl enable --now ffl-firewall.service
systemctl enable --now ffl-daemon.service
systemctl enable --now ffl-watcher.service

# Install UI as a user-level service (auto-starts with your desktop session).
USER_SYSTEMD_DIR="${BUILD_HOME}/.config/systemd/user"
mkdir -p "${USER_SYSTEMD_DIR}"
install -m 644 "${REPO_ROOT}/systemd/ffl-ui.service" "${USER_SYSTEMD_DIR}/ffl-ui.service"
sudo -u "${BUILD_USER}" systemctl --user daemon-reload
sudo -u "${BUILD_USER}" systemctl --user enable --now ffl-ui.service

echo ""
echo "FocusForLife installed. Everything starts automatically on boot."
echo ""
echo "Useful commands:"
echo "  journalctl -u ffl-daemon -f          # live daemon logs"
echo "  systemctl status ffl-daemon          # daemon status"
echo "  systemctl --user status ffl-ui       # UI status"
