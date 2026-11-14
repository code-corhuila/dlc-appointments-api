# Changelog

All notable changes of `dlc-appointments-api` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/). A release is tagged `v<version>` once it is merged into `main`.

## [Unreleased]

## [3.0.0] - 2026-11-14

The changelog starts at this release.

### Added

- Implement appointment domain model.
- Implement schedule appointment use case.
- Add scheduling consistency contracts.
- Add HTTP scheduling DTO contracts.
- Add HTTP error handling.
- Add RS256 JWT verification.
- Add Auth session validation client.
- Coordinate JWT and Auth session validation.
- Add request correlation and error envelope.
- Authenticate staff requests on the public api.
- Declare explicit server runtime limits.
- Complete the appointment aggregate state.
- Add the postgresql persistence adapter.
- Validate patient and dentist eligibility with their owners.
- Register a scheduled appointment atomically.
- Schedule appointments over http.
- Read an appointment within the reader's scope.
- List the appointment calendar within the reader's scope.
- Model dentist availability replacement.
- Serve dentist availability over http.
- Add the appointment lifecycle state machine.
- Orchestrate appointment lifecycle changes.
- Persist appointment lifecycle changes atomically.
- Serve confirmations, cancellations, attention starts and no-shows.
- Reschedule appointments with a recalculated reminder.
- Add clinical assignment domain and use cases.
- Persist clinical assignments with events and keys.
- Serve clinical assignments over http.
- Authenticate internal routes with service tokens.
- Serve internal relationship and encounter reads.
- Request due informational reminders on a schedule.
- Complete appointments after the dentist declaration.
- Serve reminder details and delivery outcomes to the worker.
- Relay the outbox to rabbitmq.
- Carry the delivery id in reminder requests.
- Serialize scheduling against the patient gate.
- Add adr-017 patient guards with token rotation.
- Serve the adr-017 patient guard operations.
- Consume care completions and patient deactivations through the inbox.
- Read consumed event envelopes strictly against their v1 schemas.
- Consume clinical and patient events from rabbitmq with bounded retries and dlq.
- Serve swagger ui over the dlc-docs contracts at /docs.
- Obtain service tokens from IAM by client credentials (ADR-020).
- Appointment-type catalog and optional type on scheduling (HU-APT-006).
- Waiting list with idempotent create and resolution (HU-APT-005).

### Fixed

- Harden container and add RabbitMQ healthcheck.
- Address automated review findings.
- Address remaining automated review findings.
- Fail closed when an owner call is cancelled by its timeout.
- Remind the new date after a delivered reminder.
