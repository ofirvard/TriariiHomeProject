# RF Alert Manager

Backend for an RF spectrum monitoring network. Sensors report the signals they detect as JSON
observations; the system validates and stores them, evaluates them against configurable rules, and manages
the resulting alerts (open, acknowledge, resolve, auto-resolve).

```
sensors ──HTTP──> nginx ──> ingest-service ──RabbitMQ──> alert-service
                    │        (observations,     queue     (alerts: dedup,
                    │         rules, engine)              lifecycle, auto-resolve)
                    └──────────────────────────────────────> /api/v1/alerts
                             both services use PostgreSQL (separate schemas)
```

| Path | What it is |
|---|---|
| `common/` | Shared models (`ObservationModel`, `RuleMatchEvent`, ...) and the queue name |
| `ingest-service/` | Observations, sensors, rules, the `power_threshold` rule engine; publishes matches |
| `alert-service/` | Consumes matches, owns alerts and their lifecycle |
| `docker-compose.yml`, `deploy/nginx.conf` | The deployed stack: nginx, both services, PostgreSQL, RabbitMQ |
| `docker-compose.dev.yml` | PostgreSQL and RabbitMQ only, for running the services from an IDE |
| `simulator.sh` | Traffic simulator |
| `deploy/gcp-vm.sh`, `deploy/provision.sh` | The deployment VM on GCP and its provisioning |
| `deploy/deploy.sh`, `deploy/smoke-test.sh` | Deploy / rollback and smoke test, run on the VM by the pipeline |

## Prerequisites

- Docker with Compose v2 (Docker Desktop, or Docker Engine on Linux)
- JDK 17, only to build or test outside Docker
- `bash`, `curl` and `awk` for the simulator

## Run the full stack with Docker

```bash
cp .env.example .env          # then set real passwords in .env
./gradlew bootJar             # the images package these jars; nothing is compiled inside Docker
docker compose up -d --build --wait
```

Everything is served by nginx on port 80 (`HTTP_PORT` in `.env` to change it). Only nginx publishes a
port; PostgreSQL and RabbitMQ are internal, with their data in named volumes.

```bash
curl localhost/health                  # ingest-service health, incl. database and broker
curl localhost/alert-service/health    # alert-service health
curl localhost/api/v1/rules
docker compose down                    # stop; data stays in the volumes (add -v to delete it)
```

## Run in development mode

Start only the infrastructure, then run the services from the IDE or Gradle. Both read the same `.env`.

```bash
cp .env.example .env
docker compose -f docker-compose.dev.yml up -d     # PostgreSQL :5432, RabbitMQ :5672 (UI :15672)
./gradlew :ingest-service:bootRun                  # http://localhost:8081
./gradlew :alert-service:bootRun                   # http://localhost:8082
```

## Run the tests

```bash
./gradlew test
```

Unit tests cover the rule engine; integration tests start real PostgreSQL and RabbitMQ with
Testcontainers, so Docker must be running.

## Run the simulator

`simulator.sh` sends a batch of observations per tick from five sensors and prints the newest alerts
every 10 seconds.

```bash
./simulator.sh -u http://localhost -a http://localhost      # against the Docker stack (via nginx)
./simulator.sh                                              # against dev mode (:8081 / :8082)
./simulator.sh -s persistent,burst -r 2 -d 60               # chosen scenarios, 2 obs/s per sensor, 60 s
./simulator.sh -h                                           # all options
```

| Option | Env var | Default | Meaning |
|---|---|---|---|
| `-u` | `INGEST_URL` | `http://localhost:8081` | ingest-service base URL |
| `-a` | `ALERTS_URL` | `http://localhost:8082` | alert-service base URL (`""` skips the alert status) |
| `-r` | `RATE` | `1` | observations per second per sensor (fractions allowed) |
| `-d` | `DURATION` | `180` | run time in seconds |
| `-s` | `SCENARIOS` | all four | comma-separated scenarios, see below |

| Scenario | Traffic | Expected result |
|---|---|---|
| `background` | 5 sensors on 433/868/915 MHz, 5 GHz and weak 2.4 GHz Wi-Fi (below -50 dBm), whole run | no alert |
| `persistent` | 2437 MHz at about -40 dBm on sensor-03, from 10 s to the end | alert opens after 10 s and stays open |
| `burst` | 2462 MHz at about -35 dBm on sensor-01, 20 s to 23 s | no alert (shorter than `min_duration_s`) |
| `disappearing` | 2452 MHz at about -43 dBm on sensor-04, 10 s to 70 s | alert opens, then auto-resolves around 140 s (`resolve_after_s` 60 + 10 s grace) |

Phase boundaries can be moved with `PERSISTENT_START`, `BURST_START`, `BURST_LENGTH`, `DISAPPEAR_START` and
`DISAPPEAR_STOP`.

## API

Behind nginx all paths share one host; in dev mode ingest-service is on `:8081` and alert-service on `:8082`.

| Endpoint | Method | Service | Description |
|---|---|---|---|
| `/api/v1/observations` | POST | ingest | One observation or an array (max 1000) |
| `/api/v1/observations` | GET | ingest | Filters: `sensor_id`, `min_mhz`, `max_mhz`, `from`, `to`, `limit` |
| `/api/v1/sensors` | GET | ingest | Known sensors and their last-seen time |
| `/api/v1/rules` | GET, POST | ingest | List or create rules |
| `/api/v1/rules/{rule_id}` | PUT, DELETE | ingest | Replace (incl. enable/disable) or delete a rule |
| `/api/v1/alerts` | GET | alert | Filters: `state`, `severity`, `sensor_id`, `min_mhz`, `max_mhz`, `limit` |
| `/api/v1/alerts/{alert_id}` | GET | alert | Alert plus its latest 100 contributing observations |
| `/api/v1/alerts/{alert_id}/ack` | POST | alert | Acknowledge (409 if already resolved) |
| `/api/v1/alerts/{alert_id}/resolve` | POST | alert | Resolve manually |
| `/health`, `/metrics` | GET | both | Health incl. database and broker; Prometheus metrics. Via nginx the alert-service ones are under `/alert-service/` |

Batches are partially accepted: valid observations are stored and invalid ones are listed by index in the
response. The status is `200` if anything was stored and `422` if nothing was. Errors use RFC 9457 problem
details.

```bash
curl -X POST localhost/api/v1/observations -H 'Content-Type: application/json' -d '{
  "sensor_id": "sensor-03", "timestamp": "2026-10-06T10:15:30Z", "frequency_mhz": 2437.0,
  "bandwidth_khz": 20000, "power_dbm": -42.5, "location": {"lat": 32.08, "lon": 34.78}}'
```

Rules are seeded at startup from `ingest-service/src/main/resources/rules.yaml` (insert if absent, so
changes made through the API survive restarts) and managed through the API afterwards.

## Configuration

All configuration is through environment variables; nothing secret is in the repository or the images.
`.env.example` lists the ones docker compose needs.

| Variable | Default | Used by |
|---|---|---|
| `DB_USER`, `DB_PASSWORD` | none (required) | both services, PostgreSQL |
| `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | none (required) | both services, RabbitMQ |
| `DB_NAME` | `rf` | both services, PostgreSQL |
| `DB_HOST`, `DB_PORT`, `RABBITMQ_HOST`, `RABBITMQ_PORT` | `localhost`, `5432`, `localhost`, `5672` | both services (set by compose) |
| `SERVER_PORT` | `8081` / `8082` (`8080` in the images) | each service |
| `RULES_FILE` | `classpath:rules.yaml` | ingest-service, e.g. `file:/config/rules.yaml` |
| `MAX_BATCH_SIZE` | `1000` | ingest-service |
| `MAX_GAP` | `5s` | ingest-service: a pending window resets after this long without a match |
| `IMAGE_TAG`, `REGISTRY`, `HTTP_PORT` | `local`, `ghcr.io/ofirvard/triariihomeproject`, `80` | docker compose |

## VM provisioning and deployment

The deployment target is an Ubuntu 24.04 VM on Google Compute Engine (`e2-medium`), created by
`deploy/gcp-vm.sh` and provisioned by `deploy/provision.sh`: its own VPC network whose firewall lets in only
SSH and HTTP, Docker with bounded logs, ufw, a `.env` of passwords generated on the VM, and a self-hosted
GitHub Actions runner. The pipeline deploys through that runner, so no SSH key or cloud credential is stored
in GitHub.

Prerequisites: a GCP project with billing enabled (the free trial credit covers this VM), and the
[gcloud CLI](https://cloud.google.com/sdk/docs/install), signed in with `gcloud auth login`.

1. Get a runner registration token: repository **Settings → Actions → Runners → New self-hosted runner**
   (Linux x64), copy the token from the `config.sh` line. It is valid for one hour.
2. Create and provision the VM (from bash: Linux, WSL or Git Bash):

   ```bash
   PROJECT=<gcp-project-id> GH_RUNNER_TOKEN=<token> deploy/gcp-vm.sh
   ```

   It prints the VM's address, and the runner `rf-vm` (label `rf-vm`) appears as Idle under Settings →
   Actions → Runners. The script is safe to run again; without `GH_RUNNER_TOKEN` it skips the runner.
   `ZONE`, `VM_NAME` and `MACHINE_TYPE` override the defaults.
3. Deploy: merge to `main`, or **Actions → Pipeline → Run workflow**. Build & Publish pushes the images,
   Deploy runs them on the VM, Verify smoke-tests the VM.
4. Use it at `http://<vm-ip>`:

   ```bash
   curl http://<vm-ip>/health
   ./simulator.sh -u http://<vm-ip> -a http://<vm-ip>
   ```

The VM restarts everything on boot (Docker, the stack, the runner). The running version is the VM's
`app-version` label (GCP console → Compute Engine → rf-vm, dots shown as dashes: `v0-3-0`), set by each
deploy and rollback. To look at it: `gcloud compute ssh rf-vm --zone us-central1-a`, then
`cd /opt/rf-alert-manager && docker compose ps`. To remove everything, delete the GCP project (or the VM,
the `rf-vm-ip` address, the `rf-net` firewall rules and network, the `rf-vm-sa` service account and the
`rfVmLabeler` role).

### Rollback

- **Automatic**: if the new containers do not become healthy (Deploy) or the smoke test fails (Verify), the
  VM goes back to the previous deployment and the pipeline fails.
- **Manual**: **Actions → Rollback → Run workflow**. Leave the version empty to return to the deployment
  before the current one, or enter any published tag (`v0.2.0`, a commit SHA). It redeploys and smoke-tests.
