# Enforcement Approach (v1)

## Goal
System-level blocking that is persistent across reboot and hard to bypass without deep system changes.

## DNS resolver choice
- Use a local validating DNS resolver (Unbound recommended for robustness).
- Resolver answers blocked domains with a sink response (NXDOMAIN or local block-page IP).

## Firewall policy (high-level)
- Force all DNS traffic to the local resolver.
- Block external DNS and encrypted DNS (DoH/DoT) where possible.
- Keep rules minimal and auditable.

## Block page
- Optional local HTTP server for an explicit “blocked” page.
- DNS sink can point blocked domains to this local server when enabled.

## Notes
- Exact rule details will be specified in configuration and systemd units.
- Avoid fragile per-app rules; prefer system-level enforcement.
