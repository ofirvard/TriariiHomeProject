# Architecture

## Components

| Component | Tech | Responsibility |
|---|---|---|
| **nginx** | `nginx:1.27-alpine` | Single entry point (port 80). Routes `/api/v1/alerts*` and `/alert-service/*` to alert-service, everything else to ingest-service. Re-resolves service names via Docker DNS, so redeployed containers are picked up without a restart. |
| **ingest-service** | Java 17, Spring Boot 3.5 | Ingests and validates observations, stores them, lists sensors, manages rules (API + `rules.yaml` seed), evaluates `power_threshold` rules and publishes matches. |
| **alert-service** | Java 17, Spring Boot 3.5 | Consumes rule matches, deduplicates them into alerts, owns the lifecycle (OPEN → ACKNOWLEDGED → RESOLVED), auto-resolves silent alerts, serves the alerts API. |
| **RabbitMQ** | `rabbitmq:3.13-alpine` | Durable queue `alerts.rule-matches` between the two services (at-least-once, consumer acks, 3 retries). |
| **PostgreSQL** | `postgres:16-alpine` | One database, one schema per service (`ingest`, `alerts`); each service owns and migrates its own schema with Flyway. |
| **common** | Java library | Shared contract: `ObservationModel`, `LocationModel`, `RuleMatchEvent`, `Severity`, the queue name. |

Both services expose `/health` (incl. database and broker) and `/metrics` (Prometheus) via Spring Boot Actuator.

## Data flow

```
 sensors / simulator.sh
        │  POST /api/v1/observations  (one object or a batch)
        ▼
 ┌────────┐      ┌──────────────────────────────────────────────┐
 │ nginx  │─────▶│ ingest-service                                │
 │  :80   │      │ 1. validate each item (partial acceptance)    │
 └────────┘      │ 2. store valid ones ─────────────────────────────▶ Postgres (schema ingest)
     │           │ 3. evaluate enabled rules, in event-time order│
     │           │    (power_threshold windows, in memory)       │
     │           │ 4. publish RuleMatchEvent per match ─────┐    │
     │           └──────────────────────────────────────────│────┘
     │                                                       ▼
     │                                          RabbitMQ  alerts.rule-matches
     │                                                       │
     │           ┌──────────────────────────────────────────▼────┐
     └──────────▶│ alert-service                                  │
   /api/v1/alerts│ 1. lock the active alert of the signal, or open one ──▶ Postgres (schema alerts)
                 │ 2. add the observations (idempotent)           │
                 │ 3. sweeper every 5 s: auto-resolve silent alerts│
                 └────────────────────────────────────────────────┘
```

### Rule evaluation (`power_threshold`)

- A match is an observation inside the rule's band with power **above** `threshold_dbm`.
- State is one window per **signal** = `(rule_id, frequency rounded to 0.1 MHz)`; that same key (`signal_key`) is what alert-service deduplicates on. Any sensor reporting the signal feeds the window.
- **Pending** window: matches are buffered; a gap larger than `MAX_GAP` (5 s) restarts it, so a short burst never adds up to `min_duration_s`.
- **Firing**: once `last − first ≥ min_duration_s` (event time) the buffered window is published as one event; every further match is published on its own. A firing window ends after `resolve_after_s` without a match — the same moment alert-service auto-resolves.
- Each batch is sorted by timestamp before evaluation; windows track `min(first)`/`max(last)`, so out-of-order data inside a window is handled.

### Alert lifecycle

- **Dedup**: a partial unique index allows at most one OPEN/ACKNOWLEDGED alert per `signal_key`; incoming matches row-lock it (`SELECT … FOR UPDATE`) and add observations, otherwise a new alert opens.
- **Acknowledged alerts keep updating** (state is untouched by new matches).
- **Auto-resolve**: alerts whose last observation (event time) is older than `resolve_after_s` + 10 s grace relative to the clock are resolved by one `UPDATE`. A later match opens a new alert.
- **Manual**: `POST …/ack` (409 if already resolved), `POST …/resolve` (idempotent).

## Data model

### ingest schema

| Table | Columns | Notes |
|---|---|---|
| `observations` | `id`, `sensor_id`, `ts`, `frequency_mhz`, `bandwidth_khz`, `power_dbm`, `lat`, `lon`, `received_at` | Indexes on `(sensor_id, ts)`, `(ts)`, `(frequency_mhz, ts)` for the query endpoint. `ts` is event time; `received_at` is arrival time. |
| `rules` | `rule_id`, `name`, `type`, `min_mhz`, `max_mhz`, `threshold_dbm`, `min_duration_s`, `resolve_after_s`, `severity`, `enabled` | Seeded from `rules.yaml` insert-if-absent. |

Sensors are not a table: `GET /api/v1/sensors` is `max(ts)` per `sensor_id` over `observations`.

### alerts schema

| Object | Columns | Notes |
|---|---|---|
| `alerts` | `alert_id`, `rule_id`, `signal_key`, `state`, `severity`, `frequency_mhz`, `resolve_after_s` | Identity and state only. Unique partial index on `signal_key WHERE state <> 'RESOLVED'`. |
| `alert_observations` | `alert_id`, `observation_id`, observation fields | Contributing observations (copies, so alert details never call ingest-service). PK `(alert_id, observation_id)` makes redelivery a no-op. |
| `alert_view` (view) | alert + `sensor_ids`, `first_seen`, `last_seen`, `occurrences`, `peak_power_dbm` | The spec's alert model, derived from the observations. |

### Message: `RuleMatchEvent` (JSON, snake_case)

```json
{ "rule_id": "rule-ism-high-power", "signal_key": "rule-ism-high-power@2437.000",
  "severity": "HIGH", "resolve_after_s": 60,
  "observations": [ { "id": 42, "sensor_id": "sensor-03", "timestamp": "...", "frequency_mhz": 2437.0, ... } ] }
```

## Deployment topology

```
 GCP project, VPC "rf-net" (firewall: tcp 22 + 80 only)          GitHub
   http://<vm-ip> ──┐                                               Actions ◀─ outbound HTTPS (long poll) ─┐
                    │                                               GHCR    ◀─ docker pull ──────────────┐ │
 ┌──────────────────▼─────────────────────────────────────────────────────────────────────┐            │ │
 │ VM rf-vm: Compute Engine e2-medium, Ubuntu 24.04, ufw 22 + 80, journald 200 MB         │            │ │
 │                                                                                        │            │ │
 │  actions runner (service, user "runner", label rf-vm) ─────────────────────────────────┼────────────┼─┘
 │     runs deploy/deploy.sh + smoke-test.sh in /opt/rf-alert-manager                     │            │
 │                                                                                        │            │
 │  Docker (on boot), compose project "rf-alert-manager", one bridge network ─────────────┼────────────┘
 │   nginx :80 ◀── only published port                                                    │
 │     ├──▶ ingest-service :8080  (512 MB limit, non-root, healthcheck)                   │
 │     └──▶ alert-service  :8080  (512 MB limit, non-root, healthcheck)                   │
 │   postgres :5432   ── volume pg-data                                                   │
 │   rabbitmq :5672   ── volume rabbitmq-data                                             │
 │   all: restart unless-stopped, json-file logs 10 MB × 3                                │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

- Startup is health-gated: services wait for healthy Postgres/RabbitMQ; nginx waits for healthy services.
- Configuration comes from `/opt/rf-alert-manager/.env` (passwords generated on the VM at provisioning, `IMAGE_TAG` set by each deploy); nothing secret is in the repo or images. `history` next to it lists the deployments that came up healthy, which rollback walks back through.
- Images: `ghcr.io/ofirvard/triariihomeproject/{ingest-service,alert-service}`, tagged with the commit SHA, the version (`v0.N.0`) and `latest`.

## CI/CD

GitHub Actions, `.github/workflows/pipeline.yml` calling one reusable workflow per stage:

```
pull request:          ci
merge to main/manual:  ci ──▶ build-publish ──▶ deploy (VM) ──▶ verify (smoke test on VM ──▶ tag version)
manual:                rollback
```

| Stage | Runs | What it does |
|---|---|---|
| `ci.yml` | every PR and merge | Checkstyle, unit + Testcontainers integration tests, shellcheck; on merges packages the jars as an artifact. Required check on `main`. |
| `build-publish.yml` | merge to main | Builds both images from the tested jars (no recompilation), pushes `:<sha>` to GHCR with layer caching. |
| `deploy.yml` | after publish | On the VM's self-hosted runner: `deploy/deploy.sh <sha>` pulls exactly the `:<sha>` images and restarts the stack (`up --wait`); if it is not healthy, restores the previous deployment. |
| `verify.yml` | after deploy | On the VM: `deploy/smoke-test.sh` through nginx; on failure rolls back and fails. On success tags `v0.N.0` + `latest` (registry re-tag, no rebuild) and creates the GitHub Release. |
| `rollback.yml` | manual | On the VM: redeploys a given tag (or the previous deployment) and smoke-tests it. |
| `rollback-drill.yml` | manual | Builds a broken release (`:rollback-drill`), runs it through `deploy.yml` and `verify.yml` (no tagging), and confirms the VM rolled back to the previous version. |
