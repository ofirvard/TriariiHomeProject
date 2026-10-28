# Decisions

## Stack

- **Java 17 + Spring Boot 3.5** instead of the preferred Python: the stack I am fastest in; Spring gives
  health, Prometheus metrics, validation, JDBC, AMQP and migrations nearly for free.
- **PostgreSQL**, one instance with a schema per service: the queries are multi-column filters (sensor +
  frequency + time; state + severity + sensor + frequency), which is plain indexed SQL.
- **JDBC (`NamedParameterJdbcTemplate`), not JPA**: explicit SQL, batch inserts, no ORM surprises.
  **Flyway**: each service migrates its own schema at startup.
- **RabbitMQ**: the traffic is task delivery to one consumer, not an event log. Durable queues, acks and
  retries by configuration, first-class Spring support, light. Kafka was overkill; Redis Streams needs more
  hand-written consumer code.
- **Service split at "detection vs. state"**: ingest-service decides a condition holds and publishes a
  self-contained event; alert-service owns dedup and lifecycle and never calls back.

## API

- **Batches are partially accepted**: readings are independent, so one bad item should not drop the rest.
  200 if anything was stored (rejected items listed with index and field), 422 if nothing was, 413 above
  1000 items. Trade-off: resending a partially stored batch duplicates the stored items.
- Errors are RFC 9457 problem details; timestamps with an offset are normalised to UTC.
- **Rules** load from `rules.yaml` at startup, insert-if-absent, so API changes survive restarts. Only
  `power_threshold` is implemented (`multi_sensor` was optional).

## Event time, late and out-of-order data

- Alert times and the `min_duration_s` window use the **observation timestamp**, never arrival time.
- A batch is evaluated in timestamp order and a window tracks `min(first)`/`max(last)`, so out-of-order
  observations inside a window count. One older than its window by more than the gap is stored but not
  counted.
- A gap over 5 s (`MAX_GAP`) restarts a pending window, so short bursts never add up to an alert.
- **Auto-resolve uses the clock**: silence cannot be seen in event time (no events arrive), so an alert
  resolves when its last observation is older than `resolve_after_s` + 10 s grace relative to now. Assumes
  sensors report in near real time.
- Same signal = same rule + frequency rounded to 0.1 MHz (2437.04 and 2437.06 fall in different buckets).

## Reliability

- At-least-once delivery, idempotent consumer: an alert's summary is derived from its stored observations,
  each stored once (primary key), so a redelivery changes nothing.
- Dedup is enforced by the database: a partial unique index allows one OPEN/ACKNOWLEDGED alert per signal,
  and the row is locked while updated.
- A failing message is retried 3 times with backoff, then logged and dropped.

## Containers

- Each image packages only the jar CI tested (no compilation in Docker): `temurin:17-jre-alpine`, non-root,
  `HEALTHCHECK`, heap sized from the memory limit.
- Compose: only nginx publishes a port; data in named volumes; health-gated startup;
  `restart: unless-stopped`; logs capped at 10 MB × 3; config from a gitignored `.env`.

## CI/CD

- GitHub Actions: `pipeline.yml` calls one reusable workflow per stage. PRs run CI only; merges to `main`
  run CI → Build & Publish → Deploy → Verify. `main` is protected and requires the CI check.
- Images are tagged with the commit SHA. Only after the smoke test passes does Verify add the next version
  (`v0.N.0`) and `latest` by re-tagging (no rebuild), so versions always point at verified builds.
- Secrets: only the built-in `GITHUB_TOKEN`. The stack's passwords are generated on the VM and never leave it.

## VM and deployment

- **Ubuntu 24.04 on GCP Compute Engine** (`e2-medium`, 4 GB; the free `e2-micro` is too small for two JVMs,
  Postgres and RabbitMQ). Created by `deploy/gcp-vm.sh` (gcloud: one VM, one network, no state file, so no
  Terraform) and provisioned by `deploy/provision.sh`. A local VirtualBox VM was tried first and dropped:
  with the Windows hypervisor always on, the guest booted 1 in 3 times and its clock ran 7-10x fast.
- **CI reaches the VM through a self-hosted runner on it.** The runner connects *out* to GitHub, so no SSH
  key or cloud credential is stored in GitHub, and it would work for a VM with no public IP. SSH from a
  hosted runner would need a key secret and port 22 open to GitHub; a pull agent (Watchtower) could not let
  Verify fail the pipeline. Only `main` and manual runs reach the runner; PRs never do.
- **Deploy** pulls exactly the `:<sha>` images and runs `up --wait`, so it fails if a container is unhealthy.
- **Rollback**: automatic when Deploy or Verify fails, manual via the Rollback workflow. It returns to the
  last deployment that came up healthy (a history on the VM, so a failed build is never a target). The
  Rollback drill workflow tests the automatic path with a broken release. Migrations must stay additive,
  because a rollback does not undo them.
- **Hardening**: firewall and ufw allow only 22 and 80; static IP; boot starts Docker, the stack and the
  runner; logs capped (Docker 10 MB × 3, journald 200 MB) and shipped to Cloud Logging by the Ops Agent.
  The VM's service account may only read and label instances (the `app-version` label) and write logs and
  metrics.
- Not zero-downtime: `up -d` recreates the services, so requests fail for a few seconds.

## Not production-ready

- Event loss if publishing to RabbitMQ fails after the observations are stored (fix: transactional outbox).
- Rule-engine windows live in memory: lost on restart, and two ingest replicas would split them.
- No dead-letter queue, retention, authentication or TLS; no backups; one DB user for both schemas.
- `GET /sensors` scans `observations`; at volume it needs its own table.
- Single VM: a single point of failure. The runner can use Docker, which is effectively root on the VM.

## With more time

1. Zero-downtime deploys (two replicas per service behind nginx) and an ephemeral runner.
2. Transactional outbox and a dead-letter queue; persisted rule-engine windows.
3. `multi_sensor` and `sensor_silence` rules.
4. Prometheus + Grafana with business metrics.
5. Managed Postgres and RabbitMQ, retention, auth and TLS.
