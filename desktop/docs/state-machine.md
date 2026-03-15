# Rule Engine State (v1)

This file explains the current runtime evaluation.

## Inputs
- Current time (local)
- Daily usage (seconds)
- Cooldown end time (optional)
- Free-time grant flag (daily)

## Outputs
- Focus state (Allowed / BlockedHardWindow / BlockedCooldown / BlockedQuota)
- Remaining daily quota
- Remaining cooldown seconds

## Notes
- Session tracking and live usage accumulation are not implemented yet.
- Free-time window behavior will be integrated after session tracking exists.
