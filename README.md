# dlc-appointments-api

> Appointments bounded context — backend service API.

Backend service for appointment management in the **Di Lucca Dental Care & Technology** distributed system: scheduling (HU-APT-001), the appointment lifecycle and reminders (HU-APT-002), the calendar (HU-APT-003) and ongoing clinical assignments (HU-APT-004).

Governance, requirements, contracts and decisions live in [`code-corhuila/dlc-docs`](https://github.com/code-corhuila/dlc-docs):

| Topic | Location in dlc-docs |
|---|---|
| Public HTTP contract (`/api/v1`) | `07-api/contracts/openapi/appointments-service.yaml` |
| Private contract (`/internal/v1`) | `07-api/contracts/openapi/internal-coordination.yaml` |
| Event schemas | `07-api/contracts/events/` |
| Data model and decisions D1-D17 | `09-microservices/services/04-appointments-service/data-model.md` |
| Published and consumed events | `09-microservices/services/04-appointments-service/events.md` |

The schema, migrations and runtime role belong to [`dlc-appointments-db`](https://github.com/code-corhuila/dlc-appointments-db); this service runs no migrations.

## Technology stack

- Java 21, Spring Boot 3.5, Maven
- PostgreSQL 16 (schema `appointments`, role `appointments_runtime`)
- RabbitMQ 3.13 (outbox relay and event consumers)
- Docker

## Architecture

Hexagonal, in three Maven modules:

- `appointments-core`: domain model, ports and use cases, with no Spring, JPA or infrastructure dependency.
- `appointments-adapters`: HTTP controllers and security, JDBC persistence, owner clients (Auth, Patients) and RabbitMQ messaging.
- `appointments-app`: Spring Boot entry point, configuration and composition root.

## HTTP surface

| Base | Callers | Authentication |
|---|---|---|
| `/api/v1/appointments`, `/api/v1/dentists/{id}/availability`, `/api/v1/clinical-assignments` | Staff, through the Gateway | Staff RS256 JWT from Auth; the session is revalidated with Auth on each request |
| `/internal/v1/...` (assignments, encounter status, patient guards, notification details and outcomes) | Clinical, Patients, `dlc-worker` | Service RS256 JWT with its own issuer, audience and scopes |
| `/health`, `/health/ready` | Probes | None |

Errors use the shared envelope `{error, message, details, traceId}`. Every response carries `X-Correlation-Id`.

## Background work

All of it runs inside the API process and is safe with several replicas (`FOR UPDATE SKIP LOCKED` and the inbox):

- **Reminder pass:** due `PENDING` reminders → `AppointmentReminderRequested`.
- **Outbox relay:** committed events → topic exchange `appointments.events`, routing key = event name.
- **Consumers:**
  - `ClinicalCareCompleted` (queue `appointments.clinical-care-completed`) records the Dentist's declaration.
  - `PatientDeactivated` (queue `appointments.patient-deactivated`) closes the patient gate.
  - Each queue has a `.dlq`. The message is acknowledged after the inbox commit.

## Configuration

Copy `.env.example` to `.env` and fill it in. Never commit `.env`, tokens, private keys or certificates.

| Variable | Purpose |
|---|---|
| `SERVER_PORT` | HTTP port (default `8080`) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `appointments` schema as `appointments_runtime` |
| `JWT_PUBLIC_KEY` or `JWT_PUBLIC_KEY_FILE`, `JWT_ISSUER`, `JWT_AUDIENCE` | Staff tokens (public key only) |
| `INTERNAL_SERVICE_JWT_PUBLIC_KEY` or `INTERNAL_SERVICE_JWT_PUBLIC_KEY_FILE`, `INTERNAL_SERVICE_JWT_ISSUER`, `INTERNAL_SERVICE_JWT_AUDIENCE` | Service tokens on `/internal/v1` |
| `AUTH_INTERNAL_URL`, `AUTH_SERVICE_TOKEN_FILE` | Session revalidation with Auth |
| `PATIENTS_INTERNAL_URL`, `PATIENTS_SERVICE_TOKEN_FILE` | Patient eligibility with Patients |
| `APPOINTMENTS_RABBITMQ_URI` | Broker URI with credentials (secret); required while the relay or the consumers are enabled |
| `APPOINTMENTS_EVENTS_EXCHANGE` | Published exchange (default `appointments.events`) |
| `APPOINTMENTS_CLINICAL_EVENTS_EXCHANGE`, `APPOINTMENTS_PATIENTS_EVENTS_EXCHANGE` | Consumed exchanges (defaults `clinical.events`, `patients.events`) |
| `APPOINTMENTS_RELAY_ENABLED`, `_INTERVAL`, `_BATCH_SIZE` | Outbox relay (`true`, `PT2S`, `100`) |
| `APPOINTMENTS_REMINDERS_ENABLED`, `_INTERVAL`, `_BATCH_SIZE` | Reminder pass (`true`, `PT30S`, `100`) |
| `APPOINTMENTS_CONSUMERS_ENABLED`, `APPOINTMENTS_CONSUMER_ATTEMPTS`, `APPOINTMENTS_CONSUMER_BACKOFF` | Consumers (`true`, `5`, `PT1S`) |
| `APPOINTMENTS_API_DOCS_DIR` | Folder `07-api/contracts/openapi` of a dlc-docs checkout. Enables Swagger UI at `/docs`; leave it empty in production |
| `APPOINTMENTS_SECRETS_DIR`, `PLATFORM_NETWORK` | Docker Compose only: host folder mounted read-only at `/run/secrets`, and the external network |

## Build and test

```bash
mvn -B verify
```

Database and broker tests are skipped unless these are set:

| Variable | Value |
|---|---|
| `TEST_DATABASE_URL`, `TEST_DATABASE_USER`, `TEST_DATABASE_PASSWORD` | A PostgreSQL 16 database migrated by `dlc-appointments-db`, as the runtime role |
| `TEST_RABBITMQ_URL` | A disposable RabbitMQ (`amqp://user:password@host:5672`) |
| `DLC_DOCS_DIR` | Path of a dlc-docs checkout. Every HTTP test response is then validated against `07-api/contracts/openapi/appointments-service.yaml` or `internal-coordination.yaml`, read in place |

Coverage (JaCoCo) is reported on every build: per module in `target/site/jacoco`, and for the whole service, counting every module's tests, in `appointments-app/target/site/jacoco-aggregate`. With the variables above set, `mvn -B verify -Pcoverage` fails when a module drops below its baseline (`coverage.line.minimum` and `coverage.branch.minimum` in each module's `pom.xml`).

## Run with Docker Compose

Requirements:
- the external network `platform`;
- PostgreSQL from `dlc-infra-postgres`, with the schema applied by `dlc-appointments-db`;
- a `.env` file at the repository root. Pass it with `--env-file .env`: Compose otherwise looks for `.env` next to `deploy/compose.yml`, and `APPOINTMENTS_SECRETS_DIR` and the broker credentials are read from it.

```bash
docker compose --env-file .env -f deploy/compose.yml up -d --build
```

If the stage has no shared broker yet, add the `broker` profile. It starts `dlc-rabbitmq` on the same network:

```bash
docker compose --env-file .env -f deploy/compose.yml --profile broker up -d --build
```

The image is multi-stage, runs as the non-root user `appointments` and listens on `SERVER_PORT`. The container healthcheck calls `GET /health`. `GET /health/ready` also checks the database.

## API documentation (Swagger UI)

Swagger UI is served at `/docs` only when `APPOINTMENTS_API_DOCS_DIR` is set; without it, `/docs` does not exist. It shows the dlc-docs contracts read in place (contract-first), not a specification generated from the code:

- **Public API (`/api/v1`):** `appointments-service.yaml`.
- **Internal API (`/internal/v1`):** `internal-coordination.yaml`, which also lists the routes of other owners.

The UI assets are bundled in the image, with no CDN. "Try it out" calls this same service, so each request still needs a valid staff or service token (**Authorize** button).

To run it with Docker Compose in dev or qa, set `DLC_DOCS_DIR` in `.env` to a dlc-docs checkout and add `deploy/compose.docs.yml`. The UI is then at `http://127.0.0.1:8090/docs` (`APPOINTMENTS_DOCS_PORT`), published on the loopback interface only:

```bash
docker compose --env-file .env -f deploy/compose.yml -f deploy/compose.docs.yml up -d --build
```

## Branching

Permanent branches do not accept direct commits.

```text
develop  <--PR--  feat/... fix/... test/... chore/...
qa       <--PR--  promotion/qa-appointments-api-N
main     <--PR--  release/... hotfix/...
```

Promotion re-applies `develop` commits with `git cherry-pick -x`; permanent branches are never merged into each other. See `00-governance/branching-policy.md` in dlc-docs.
