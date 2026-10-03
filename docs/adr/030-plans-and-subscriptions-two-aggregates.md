# ADR-030: Plans and Subscriptions Are Two Aggregates; a Plan Is a List of Feature Limits

## Status
Accepted

## Context
The product has to separate a free homelab edition from a team or enterprise one without forking code (blueprint:
"direitos por edicao"). Services only need to ask "may this organisation use X?". Nothing about money, invoices or a payment
provider is decided yet, and none of it is needed to answer that question.

## Decision
- Two top-level aggregates in one Service Domain, as `workflow-approval` does with `policy/`:
  - **`Subscription`** is the Control Record: one organisation, one plan (by its `code`), one status. Routes live at the root
    (`/subscription-billing/v1/{id}/...`) and are tenant-scoped.
  - **`Plan`** is platform-wide (no tenant): a `code` (`TEAM`, `ENTERPRISE`...), a name and `entitlements`. Routes live under
    `/plan`. Creating or changing plans is a platform operator's job, not a tenant's.
- A plan's `entitlements` is a map `feature -> limit`: **absent or `0` means the feature is not in the plan, `-1` is unlimited,
  a positive number is the allowed quantity** (assets, sites, seats...). A pure on/off feature is `1`. One shape covers both
  switches and quotas, so adding a quota later changes data, not code. Feature names are lower-case (`assets`, `discovery`,
  `sso.saml`); the domain validates them.
- A subscription refers to its plan by `code`, not by id: the code is the stable name a person recognises, and it is unique.
- Every mutation appends an immutable audit entry atomically with the change (ADR-002); nothing is deleted.
- **No money.** There are no prices, invoices, currencies or payment-provider calls. `PAST_DUE`, `SUSPENDED` and `CANCELLED` are
  states an operator (or a future billing integration) moves a subscription into; this service records them and honours them.
- **No events.** The entitlement lookup is a synchronous query (ADR-032). An outbox and NATS would be machinery without a
  consumer today; they can be added when a service needs to react to a plan change.

## Consequences
- Positive: the whole decision surface is small and testable; limits are data; a future payment integration only has to drive
  the subscription's status.
- Negative: the platform does not charge anyone yet. Plans are global, so per-tenant custom plans need a different design.
  Nothing outside this service and the web app enforces a limit yet: that is a deliberate follow-up per service.
