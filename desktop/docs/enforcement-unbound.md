# Enforcement: Unbound (v1)

## Choice
Use Unbound as the local validating DNS resolver for blocking and sinkholing.

## High-level flow
- Daemon maintains a blocklist file.
- Unbound reads the blocklist and responds with a sink result (NXDOMAIN or local block page IP).
- Firewall/DNS redirection ensures all DNS queries go to Unbound.

## Integration points
- Unbound config lives in a dedicated FocusForLife config directory.
- Blocklist file path is referenced by Unbound config.
- Daemon owns updates to the blocklist file and signals Unbound to reload.

## Notes
- Exact firewall rules are defined elsewhere and kept minimal.
- Block page is optional.
