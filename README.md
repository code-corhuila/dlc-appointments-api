# dlc-appointments-api

> Appointments bounded context — backend service API.

Backend service for appointment management in the **Di Lucca Dental Care & Technology** distributed system.

Project governance, architecture, requirements, contracts, and technical documentation are maintained in [`code-corhuila/dlc-docs`](https://github.com/code-corhuila/dlc-docs).

## Technology stack

- Java 21
- Spring Boot 3.5
- Maven
- PostgreSQL
- RabbitMQ
- Docker

## Architecture

The service follows Hexagonal Architecture and is divided into three Maven modules:

- `appointments-core`: domain, ports, and use cases.
- `appointments-adapters`: HTTP and infrastructure adapters.
- `appointments-app`: Spring Boot entry point and composition root.

`appointments-core` must not depend on Spring, JPA, or infrastructure frameworks.

## Build

From the repository root:

```bash
mvn clean verify
```

## Run locally

```bash
java -jar appointments-app/target/appointments-app-0.1.0-SNAPSHOT.jar
```

The default port is `8080`.

## Health endpoints

```text
GET /health
GET /health/ready
```

Example:

```bash
curl http://localhost:8080/health
curl http://localhost:8080/health/ready
```

## Environment configuration

Use `.env.example` as the reference for local configuration.

Never commit real passwords, tokens, private keys, certificates, or `.env` files.

## Database ownership

Database schema migrations do not belong in this repository.

The authoritative Appointments schema is managed by the dedicated database repository.

## Branching

Permanent branches do not accept direct commits.

```text
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/... hotfix/...
```

Promotion between environments uses re-application with `git cherry-pick -x`.

Permanent branches are never merged directly into each other.

For the complete policy, see `00-governance/branching-policy.md` in `dlc-docs`.