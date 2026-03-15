# Rule Engine Spec (v1)

## Core concepts
- Target: blocked domain or group.
- Session: continuous time spent on targets without exceeding idle tolerance.
- Quota: daily total allowed time across all targets.
- Cooldown: enforced idle time after a session hits the continuous limit.
- Hard window: absolute blocked time range (sleep time).
- Free-time windows: daily windows that behave like the hourly 10-minute free time, gated by prompts.

## Definitions
- On target = the system observes traffic to any blocked domain.
- Idle tolerance = grace period after last target access before a session ends.

## Rule precedence (highest to lowest)
1. Hard block window (sleep time)
2. Cooldown
3. Quota exhaustion
4. Allowed

## State behavior (high level)
- Allowed when inside focus rules and not in hard block/cooldown and quota remains.
- Active session starts on target access while Allowed.
- Session ends after idle tolerance or by hitting the continuous limit.
- Cooldown begins when continuous limit is hit.
- Quota exhaustion blocks immediately and ends session.

## Daily free-time windows (gated)
- Evening window: 21:00–23:00 local time.
- Afternoon break: 15:00–16:00 local time.
- At each window start show prompt: “Done with work?”
  - Yes -> enable free-time behavior for the window.
  - No -> do not enable free-time behavior.
  - No response within timeout -> enable free-time behavior (default grant).
- Free-time behavior = same as the hourly 10-minute free time.

## Default values
- Daily quota: 60 minutes
- Continuous limit: 10 minutes
- Cooldown: 60 minutes
- Hard block window: 23:00–11:00
- Idle tolerance: 90 seconds (proposal)

## Edge cases
- Window spans midnight (23:00–11:00).
- Day rollover resets daily quota at local midnight.
- Clock changes: store UTC, render local.
