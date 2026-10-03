# micronaut-subscription-billing-service

BIAN-aligned Service Domain **subscription-billing** (Control Record: `Subscription`), port `8095`.

Which edition each organisation is on, and what that edition allows. A **plan** (`HOMELAB`, `TEAM`, `ENTERPRISE`...) lists
`feature -> limit`; a **subscription** puts one organisation on one plan; `entitlement/evaluate` answers "may this organisation use
this feature, and up to how much?" without anyone forking code per edition (ADR-030, ADR-032).

## What it does, and what it does not

- **It decides entitlements.** Nothing else in the platform enforces a limit yet: callers (the web app today) ask this service.
- **It does not charge anyone.** No prices, invoices, currencies or payment-provider calls, and no events. `PAST_DUE`, `SUSPENDED` and
  `CANCELLED` are states an operator moves a subscription into; the service records and honours them (ADR-030).
- **It fails open.** An organisation with no subscription gets the default plan (`HOMELAB`); with no usable default plan the answer is
  `UNMANAGED, allowed` - a catalogue nobody has set up never locks anyone out (ADR-032).
- **A plan's limit is a number**: absent or `0` = not included, `-1` = unlimited, positive = the allowed quantity (`1` for an on/off feature).

## BIAN Behavior Qualifier Contract

### Subscription - `/subscription-billing/v1` (every call tenant-scoped by `X-Tenant-Id`; mutations need `X-Executor`)

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /subscription-billing/v1/initiate` `{"planCode":"TEAM"}` (starts `TRIALING`) |
| retrieve | `GET /subscription-billing/v1/{id}/retrieve` |
| retrieve (collection) | `GET /subscription-billing/v1/retrieve?status=` (newest first) |
| current/retrieve | `GET /subscription-billing/v1/current/retrieve` (404 when the tenant has none) |
| plan/update | `PUT /subscription-billing/v1/{id}/plan/update` `{"planCode":"ENTERPRISE"}` |
| control/activate | `PUT /subscription-billing/v1/{id}/control/activate` (TRIALING, PAST_DUE or SUSPENDED -> ACTIVE) |
| control/mark-past-due | `PUT /subscription-billing/v1/{id}/control/mark-past-due` (ACTIVE -> PAST_DUE) |
| control/suspend | `PUT /subscription-billing/v1/{id}/control/suspend` (ACTIVE or PAST_DUE -> SUSPENDED) |
| control/cancel | `PUT /subscription-billing/v1/{id}/control/cancel` (any non-cancelled -> CANCELLED, terminal) |
| audit-log/retrieve | `GET /subscription-billing/v1/{id}/audit-log/retrieve` |
| entitlement/evaluate | `GET /subscription-billing/v1/entitlement/evaluate?feature=assets` |

```bash
curl "http://localhost:8095/subscription-billing/v1/entitlement/evaluate?feature=assets" -H "X-Tenant-Id: <organisationId>"
# {"feature":"assets","allowed":true,"limit":500,"source":"SUBSCRIPTION","planCode":"TEAM"}
# source: SUBSCRIPTION | DEFAULT_PLAN | SUSPENDED | UNMANAGED
```

### Plan - `/subscription-billing/v1/plan` (platform-wide, no tenant; mutations need `X-Executor`)

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /subscription-billing/v1/plan/initiate` `{"code":"TEAM","name":"Team","entitlements":{"assets":500,"discovery":1,"sites":-1}}` |
| retrieve | `GET /subscription-billing/v1/plan/{id}/retrieve` |
| retrieve (collection) | `GET /subscription-billing/v1/plan/retrieve?status=` |
| update | `PUT /subscription-billing/v1/plan/{id}/update` (DRAFT only) |
| control/activate | `PUT /subscription-billing/v1/plan/{id}/control/activate` (DRAFT -> ACTIVE) |
| control/retire | `PUT /subscription-billing/v1/plan/{id}/control/retire` (ACTIVE -> RETIRED, terminal) |
| audit-log/retrieve | `GET /subscription-billing/v1/plan/{id}/audit-log/retrieve` |

A plan is editable only in `DRAFT`; a `RETIRED` plan keeps serving its subscriptions but cannot be chosen for a new one (ADR-031).
An organisation has at most one non-cancelled subscription, enforced by a partial unique index (ADR-031).

## Configuration

| Setting | Env | Default | Meaning |
|---|---|---|---|
| `thinklab.billing.default-plan-code` | `THINKLAB_BILLING_DEFAULT_PLAN_CODE` | `HOMELAB` | The plan standing in for an organisation with no subscription; only counts if it exists and is ACTIVE |
| `mongodb.uri` | `MONGODB_URI` | `mongodb://localhost:27017/thinklab_subscription_billing_db` | MongoDB (single-node replica set locally) |

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-SUB-00404` | 404 | Subscription or plan not found (another tenant's subscription answers the same) |
| `ERR-SUB-00409` | 409 | Illegal lifecycle move, duplicate plan code, organisation already subscribed, or plan not on sale (ADR-019) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure (bad plan code, malformed feature name, limit below -1...) |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
