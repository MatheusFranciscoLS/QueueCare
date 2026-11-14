# Architecture

A modular Spring Boot monolith plus a React single-page app, talking over a REST/JSON API, backed by PostgreSQL. (Portuguese original: [arquitetura.md](arquitetura.md).)

```
Browser (React + TypeScript, static files)
     |  HTTPS, JSON, Bearer token (JWT) kept only in memory
     v
Spring Boot API (Java 21)  --JPA/Flyway-->  PostgreSQL 16
  |- web          controllers, DTOs, error handling (problem+json with a stable `reason` code)
  |- application  services holding the business rules and transactions
  |- persistence  entities and repositories (Spring Data)
  |- security     Spring Security (JWT resource server), rate limits, filters
```

## Roles and access
**ADMIN** (accounts only, no access to schedules), **PROVIDER** (business, services, working hours, team, schedule), **ATTENDANT** (schedule, check-in, completion, queue) and **CUSTOMER** (catalog, booking, my appointments). Authorization is enforced **in the API** (route rules and `@PreAuthorize`), not only in the UI. The business a staff member operates on is always resolved from the database, never from the request (ADR 0004).

## Key decisions (ADRs in `docs/adr/`, written in Portuguese)
| ADR | Decision |
|---|---|
| 0002 | No double booking or overlap, even under concurrency: a PostgreSQL exclusion constraint (`btree_gist`) |
| 0003 | Short-lived HS256 JWT, checked against the database on every request; per-minute rate limits and login lockout |
| 0004 | Staff-to-provider link resolved server-side |
| 0005/0006/0007 | Account provisioning, credential lifecycle, admin operations and an append-only audit log |
| 0008/0009 | Frontend: token only in memory; screen patterns (confirmed state, errors at list level, focus management) |
| 0010 | Read-only catalog for customers |
| 0011 | Check-in, completion and the daily queue (forward-only transitions, one event per type) |
| 0012 | OpenAPI contract generated from the code and verified by a test |
| 0013 | Zero-cost, no-credit-card hosting (Cloudflare Pages + Render Free + Neon Free) |

## Concurrency and integrity (summary)
Two layers: validation in the application (transaction) **and** a guarantee in the database (an exclusion constraint over `tstzrange`). Optimistic locking (`version`) on appointment transitions; row locks and a per-transaction `lock_timeout` on admin operations (all transactional, so they are compatible with connection poolers). History events are unique per appointment and type.

## Time zones
Instants are stored in UTC (`timestamptz`); each provider has an IANA time zone. The customer sends the business's LOCAL time and the API resolves it (a nonexistent or ambiguous daylight-saving time is rejected, never guessed). The UI shows dates and times in the business time zone.

## Out of scope for the MVP
Microservices, messaging, multiple locations, payments, notifications, real-time updates.
