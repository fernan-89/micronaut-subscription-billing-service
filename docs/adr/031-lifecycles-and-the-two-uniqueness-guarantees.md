# ADR-031: Lifecycles, and the Two Uniqueness Guarantees

## Status
Accepted

## Context
Two rules must hold even under concurrent writers: a plan code names exactly one plan, and an organisation has at most one
current subscription. A use case can check first, but check-then-write is not atomic.

## Decision
- **Plan** lifecycle: `DRAFT -> ACTIVE -> RETIRED`. Name and entitlements are editable only in `DRAFT`: an `ACTIVE` plan is a
  contract tenants are subscribed to now, and rewriting it under them would change what they bought. A `RETIRED` plan keeps
  serving the subscriptions already on it but cannot be chosen for a new one or a plan change. There is no way back from
  `RETIRED`: a new version is a new code.
- **Subscription** lifecycle: starts `TRIALING`; `TRIALING|PAST_DUE|SUSPENDED -> ACTIVE`; `ACTIVE -> PAST_DUE`;
  `ACTIVE|PAST_DUE -> SUSPENDED`; any non-cancelled status `-> CANCELLED` (terminal). The plan can change in any
  non-cancelled status, onto an `ACTIVE` plan different from the current one. Every move is a named Behavior Qualifier
  (`control/activate`, `control/mark-past-due`, `control/suspend`, `control/cancel`, `plan/update`), not a generic status
  endpoint, because they are different business acts.
- Illegal moves are `409 ERR-SUB-00409`, refused by the aggregate **before** any write. A plan that does not exist or is not
  `ACTIVE` when a subscription is started or moved is also `409` (the request is well formed; the catalogue refuses it).
- **Plan codes are unique**: a unique index on `plans.code`. A duplicate is `409`.
- **One current subscription per organisation**: a **partial** unique index on `subscriptions.organisationId`, scoped to the
  non-cancelled statuses (`status in [TRIALING, ACTIVE, PAST_DUE, SUSPENDED]`). A partial filter is required, not a plain
  unique index, exactly because any number of `CANCELLED` documents per organisation are legal history; cancelling frees the
  slot for a new subscription.
- The use cases check first (a clear error in the common case: "already has a subscription (ACTIVE)") and the repository
  translates the index's duplicate-key error into the same exception, so two concurrent initiations leave exactly one winner.
  The indexes are created at startup, fail-open like the kit's initializer (`thinklab.mongo.create-indexes=false` turns it off
  for unit-test contexts).
- A subscription is only ever read or changed on behalf of its own tenant: another tenant's id answers `404`, like a missing one.

## Consequences
- Positive: the database is the arbiter of both rules; the audit trail cannot diverge from state; a foreign tenant learns
  nothing about the existence of an id.
- Negative: if the indexes fail to build (existing duplicates), the application still starts and the use-case check is the
  only guard until someone fixes the data; the log says so.
