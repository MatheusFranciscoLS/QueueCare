# QueueCare

Scheduling and queue management for small service providers (repair shops, small clinics, barbershops, consultancies). Portuguese version: [README.md](README.md).

> A full-stack portfolio project: React + TypeScript, Java 21 + Spring Boot, PostgreSQL. **Built with substantial AI assistance** (see "Authorship" below).

## What it does
- **Customers** browse providers, pick a service, day and free slot, book, see "my appointments" and cancel within the provider's cancellation window.
- **Providers** configure their business (time zone, minimum notice, cancellation window), services, working hours and team, and run the daily schedule.
- **Attendants** (staff linked to one provider) see the day's schedule, register arrivals (check-in), complete appointments and follow the arrival-ordered queue.
- **Administrators** create provider accounts and manage accounts (search, activate/deactivate, reset password) without any access to schedules or business data.

## Technical highlights
- **No double booking, even under concurrency:** two layers, application validation plus a PostgreSQL **exclusion constraint** (`btree_gist` over `tstzrange`); two simultaneous requests for the same slot yield one success and one `409`. Tested against a real PostgreSQL.
- **Authorization in the API**, not only in the UI: role rules on routes and `@PreAuthorize`; the provider a staff member operates on always comes from the database. For appointment and attendance operations, touching a record that belongs to another provider returns the same `404` as a missing record.
- **Time zones done properly:** UTC storage, IANA zone per provider, local-time input with explicit handling of nonexistent/ambiguous daylight-saving times.
- **Credential lifecycle:** short-lived JWT validated against a per-user version on every request, forced password change, rate limits and login lockout, append-only audit log.
- **Frontend discipline:** token only in memory; every mutation handles stale responses, partial failures, double submits and focus management; each pattern is covered by tests that were verified with manual mutation checks (a test must fail when the behavior is removed).
- **API contract generated from code** and verified in CI: [`docs/openapi.json`](docs/openapi.json) (roles per operation are read from the real `@PreAuthorize`).

## Tests
Backend: 456 tests (JUnit, MockMvc, real embedded PostgreSQL, concurrency races). Frontend: 262 tests (Vitest, Testing Library). CI also builds the production Docker image, scans for secrets (Gitleaks) and runs lint/build. Not done yet: browser end-to-end tests (Playwright).

## Stack
React 19 + TypeScript + Vite · Java 21 · Spring Boot 4 · Spring Security (JWT) · Spring Data JPA · Flyway · PostgreSQL 16 · Docker · GitHub Actions.

## Run locally
Prerequisites: JDK 21, Node 22+, Docker (or PostgreSQL 16).
```bash
cp .env.example .env                       # change the values; never commit .env
docker compose --env-file .env -f infra/docker-compose.yml up -d
cd backend && ./mvnw verify                # build and tests (does not keep the API running)
```
Start the API (terminal 1) and the frontend (terminal 2). The API needs `DB_URL`, `DB_USER`, `DB_PASSWORD` and `JWT_SECRET` (see `.env.example`; `JWT_SECRET` must be Base64 of at least 32 random bytes, e.g. `openssl rand -base64 32`, so replace the placeholder first):
```bash
# terminal 1: load the variables from .env and run the API on http://localhost:8080
set -a; source .env; set +a
cd backend && ./mvnw spring-boot:run

# terminal 2: the frontend on http://localhost:5173 (proxies /api to :8080)
cd frontend && npm ci && npm run dev
```
The first administrator is created once with an explicit command (see [ADR 0005](docs/adr/0005-provisionamento-de-contas.md)); no migration inserts users.

## Deployment (zero cost, no credit card)
Cloudflare Pages (static site) + Render Free (API in a Docker image) + Neon Free (PostgreSQL). A step-by-step guide is in [`docs/publicacao.md`](docs/publicacao.md) (Portuguese); the reasoning, limits and a measured profile (the API starts in ~95 s and uses ~250 MB under 512 MB / 0.1 CPU) are in [ADR 0013](docs/adr/0013-hospedagem-custo-zero.md). It is a showcase that sleeps when idle, not production availability.

## Documentation
[Architecture](docs/architecture.en.md) · [Business rules](docs/regras.md) (PT) · [ADRs](docs/adr/) (PT) · [Study guides with interview questions](docs/estudo/) (PT) · [OpenAPI](docs/openapi.json).

## Authorship
Developed under the direction of the product owner (Matheus Francisco) with **substantial AI assistance**: Claude implements the technical work (including business rules and tests) and ChatGPT performs an independent review of every change before it is merged; the owner defines the product, approves the direction and receives a summary of each delivery. This is **not** a demonstration of individual implementation. The process is described in [`CLAUDE.md`](CLAUDE.md).

## License
[MIT](LICENSE).
