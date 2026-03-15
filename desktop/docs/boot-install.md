# Boot Install

This project can now be installed as boot-time services with one command:

```bash
sudo bash scripts/install_boot_services.sh
```

What it does:

- builds and installs `ffl-daemon`
- generates and installs the Unbound and nftables runtime assets
- installs:
  - `ffl-resolver.service`
  - `ffl-firewall.service`
  - `ffl-daemon.service`
- enables and starts those services

Daemon behavior after install:

- starts automatically on Ubuntu boot
- polls browser history from `/home/user`
- tracks Brave, Chrome, Chromium, and Firefox history databases when present
- uses the existing blocked-domain list for activity detection

Useful checks:

```bash
systemctl status ffl-daemon.service --no-pager
journalctl -u ffl-daemon.service -f
systemctl status ffl-resolver.service --no-pager
systemctl status ffl-firewall.service --no-pager
```
