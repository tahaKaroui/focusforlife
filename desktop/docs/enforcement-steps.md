# Enforcement Steps (v1)

1) DNS resolver (Unbound)
- Use a dedicated Unbound config and blocklist file managed by the daemon.
- Keep resolver listening on localhost only.

2) Daemon blocklist updates
- Daemon writes blocklist entries to the configured include file.
- Daemon signals Unbound to reload after updates.

3) Systemd wiring
- Add `ffl-resolver.service` as a system unit.
- Ensure daemon starts after resolver is healthy.

Notes: Firewall/DNS redirection details are specified in a separate hardening plan.
