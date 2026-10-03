# ADR-032: Entitlement Evaluation Is a Synchronous Query and Fails Open

## Status
Accepted

## Context
`GET entitlement/evaluate?feature=X` is the one route other services and the web app call. It sits next to the
`ci-type-catalog` lookup in spirit (ADR-027 of the asset registry): a new, optional service must not become a reason the rest of
the platform stops working.

## Decision
The answer is `{feature, allowed, limit, source, planCode}`, decided in this order:
1. The organisation has a **current subscription**. If it is `SUSPENDED`, nothing is allowed (`source: SUSPENDED`). Any other status
   is decided by its plan (`source: SUBSCRIPTION`); `PAST_DUE` is deliberately a **grace period**: it still gets what its plan
   grants until an operator suspends it.
2. **No subscription**: the default plan stands in (`thinklab.billing.default-plan-code`, `HOMELAB`) if it exists and is
   `ACTIVE` (`source: DEFAULT_PLAN`), so a tenant nobody has subscribed yet keeps working with the free edition's limits.
3. **Nothing to decide with** - no usable default plan, or a subscription whose plan vanished: `allowed: true`, `source:
   UNMANAGED`. This is the **fail-open** choice: a catalogue that has not been set up must never lock anyone out.

Rules inside the answer: a feature absent from the plan or set to `0` is `allowed: false`; `-1` is allowed with `limit: null`;
a positive number is allowed with that `limit`. A missing feature name is a `400`.

## Consequences
- Positive: nothing breaks the day this service is introduced; a callable, cheap, side-effect-free query.
- Negative: fail-open means a mistake in the catalogue grants rather than denies; an operator who wants the opposite needs to
  configure a default plan with the features switched off. A caller that cannot reach this service at all decides for itself
  (the same posture as the asset registry's catalogue lookup) - enforcing limits in other services is a per-service follow-up.
