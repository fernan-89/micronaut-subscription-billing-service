# ADR 033: Subscription changes are guarded writes

## Status
Accepted (2026-11-17). Found by the platform race audit (ACTION-PLAN 0.5, `thinklab-platform/docs/race-audit.md`).

## Context
A subscription change read the subscription, validated it in the domain and wrote it by `_id` only. Measured by `SubscriptionRaceIT` on the old code:
ten activations at once gave five OK answers (and a trail of six entries); an activation and a cancellation at once both answered OK; ten plan changes
made from the same plan all answered OK and each audit entry said "Plan changed from <the original plan>", so the trail told a history that never
happened and whichever write landed last decided the plan and the entitlements.

## Decision
- `updateStatus` is a **guarded write** on the status the caller read (the `fromStatus` of the audit entry it carries).
- `updatePlan` is guarded on the **plan and the status** the caller read (the use case now passes the plan it read), because a plan change does not move
  the status and two changes made from the same plan must not both apply.
- No match is `409 ERR-SUB-00409` when the subscription exists and `404` when it does not (ADR-019).

## Consequences
- **Positive:** one winner per race, and the plan-change trail is always one unbroken chain (each "from" is the previous "to"), so the stored plan and
  the entitlements it grants are the last winner's.
- **Note:** changes that are truly sequential (the second read the subscription after the first was written) all apply, in order. The one-current-subscription
  rule per organisation is unchanged (ADR-031, partial unique index).
