# Decisions

Key decisions and trade-offs, what is not production-ready, and what I would improve with more time.

## Language, framework, database, broker

- **Java 17 + Spring Boot 3.5** instead of the preferred Python: it is the stack I am fastest and most
  accurate in, and Spring gives health, Prometheus metrics, validation, JDBC, AMQP and migrations nearly for
  free. Java 17 because that is the installed JDK; nothing here needs 21.
- **PostgreSQL** for both services (separate schemas, one instance): the queries are multi-column filters
  (sensor + frequency range + time range, state + severity + sensor + frequency) that are plain indexed SQL.
  Redis was considered as the only store but would have needed hand-built secondary indexes. One instance
  with a schema per service keeps the services' data separate without a second database container.
- **JDBC (`NamedParameterJdbcTemplate`) instead of JPA**: batch inserts with generated ids, explicit SQL,
  no ORM surprises.
- **Flyway**: each service migrates its own schema at startup.
- **RabbitMQ** as the broker: the traffic is task distribution to one consumer, not an event log.
  It gives durable queues, acks and retries through configuration, has first-class Spring support and is
  light enough for a small host. Kafka/Redpanda (replay, partitions) were overkill; Redis Streams needs more
  hand-written consumer code; Postgres-as-queue would have avoided a component but reads as dodging the
  "choose a broker" requirement.
- **Two services split at "detection vs. state"**: ingest-service decides that a condition holds and
  publishes a self-contained event (severity, `resolve_after_s`, observation copies); alert-service owns
  dedup and lifecycle and never calls back into ingest-service.

## API behaviour

- **Batches are partially accepted**: observations are independent readings, so one bad reading should not
  throw away the good ones. Each rejected item is reported with its index and field. Status is **200** if
  anything was stored and **422** if nothing was (207 is a WebDAV code; bulk APIs such as Elasticsearch
  `_bulk` and Kinesis `PutRecords` also return 200 with per-item results). Trade-off: resending a partially
  stored batch duplicates the stored items (no per-observation idempotency key).
- Batch limit 1000 items (413 above that); errors are RFC 9457 problem details.
- Timestamps are ISO-8601; values with an offset are normalised to UTC.
- **Rules**: loaded from `rules.yaml` at startup with insert-if-absent semantics, so changes made through
  the API survive restarts; an invalid file fails startup. Only `power_threshold` is implemented
  (`multi_sensor` was optional and skipped).

## Event time, late and out-of-order data

- All alert times (`first_seen`, `last_seen`) and the `min_duration_s` window use the **observation
  timestamp**, never arrival time.
- Each batch is evaluated in timestamp order; a window tracks `min(first)` / `max(last)`, so out-of-order
  observations inside a window count correctly. An observation older than its window by more than the gap
  is stored but not added to it.
- `min_duration_s` needs persistence, not just two points: a gap larger than 5 s (`MAX_GAP`) restarts a
  pending window, so two short bursts far apart never add up to an alert.
- **Auto-resolve uses the clock to detect silence**: absence of data cannot be observed in event time
  (no events arrive), so an alert resolves when its last observation (event time) is older than
  `resolve_after_s` + 10 s grace relative to now. This assumes sensors report in near real time.
  A pure event-time alternative (ingest publishes a watermark; alerts resolve against it) is listed below.
- "Same signal" = same rule + frequency rounded to 0.1 MHz. Boundary effect: 2437.04 and 2437.06 MHz fall
  into different buckets.

## Reliability

- **At-least-once delivery, idempotent consumer**: an alert's summary (occurrences, peak, sensors,
  first/last seen) is derived from its stored observations, and an observation is stored once per alert
  (primary key), so a redelivered event changes nothing.
- **Dedup is enforced by the database**: a partial unique index allows one OPEN/ACKNOWLEDGED alert per
  signal; the active alert is row-locked while an event updates it.
- A failing message is retried 3 times with backoff, then logged and dropped (never requeued forever).

## Containers

- One Dockerfile per service, each packaging only the jar Gradle built and CI tested (no compilation in
  Docker, build context = the jar). `eclipse-temurin:17-jre-alpine`, non-root user, `HEALTHCHECK` on
  `/health`, heap sized from the container memory limit (`MaxRAMPercentage=75`).
- Compose: only nginx publishes a port; Postgres and RabbitMQ data in named volumes; health-gated
  startup; `restart: unless-stopped`; `json-file` logs capped at 10 MB × 3 per container; 512 MB memory
  limit per service. All configuration via environment variables from a gitignored `.env`.

## CI/CD

- GitHub Actions, one entry workflow (`pipeline.yml`) calling one reusable workflow per stage.
  PRs run CI only; merges to `main` (or a manual run) run CI → Build & Publish → Deploy → Verify.
- **Merge blocking**: the repository is public so branch protection is available; `main` requires a PR
  and a passing `ci / Lint and test`.
- **Lint**: a deliberately small Checkstyle rule set plus shellcheck.
- **Images are tagged with the commit SHA** at publish; only after the smoke test passes does Verify add
  the next version (`v0.1.0`, `v0.2.0`, … minor bump, shared by both images) and `latest` by re-tagging in
  the registry (no rebuild), and create a GitHub Release. So `latest` and versions always point at
  verified builds; tagging is serialized so two runs never take the same version.
- **Caching**: Gradle build cache (persisted by `setup-gradle`) so unchanged modules are neither recompiled
  nor retested; reproducible jars so an unchanged module gives a byte-identical jar; Docker layer cache
  (GHA backend) so an unchanged jar is not re-packaged.
- Secrets: the pipeline uses only the built-in `GITHUB_TOKEN` (GHCR push and pull). The stack's passwords
  are generated on the VM at provisioning and never leave it.

## VM and deployment

- **VM: Ubuntu 24.04 on Google Compute Engine** (`e2-medium`, 2 vCPU / 4 GB), created by `deploy/gcp-vm.sh`
  (gcloud) and provisioned by `deploy/provision.sh`. A local VirtualBox VM (Vagrant) was tried first, since
  Windows Home has no Hyper-V: on this host the Windows hypervisor is always on (virtualization-based
  security, WSL2), so VirtualBox runs on top of it without VT-x. The guest booted only about 1 in 3 times
  (hangs in early boot) and its clock ran 7-10x fast, which breaks every timer this system depends on.
  Turning the Windows hypervisor off would fix that but also disables WSL2 and Docker Desktop. A cloud VM
  avoids all of it. The free-tier `e2-micro` (1 GB) is too small for two JVMs, Postgres and RabbitMQ.
  A gcloud script rather than Terraform: one VM, one network, two firewall rules, no state file to keep.
- **Network**: a dedicated VPC whose firewall allows only tcp 22 and 80 (the `default` network also opens
  RDP and ICMP), plus ufw on the host. SSH stays open for `gcloud compute ssh`; IAP-only SSH would be tighter.
  A static IP, so a stop/start keeps the address.
- **Version visible in GCP**: each deploy sets the VM's `app-version` label. The VM runs as its own service
  account (`rf-vm-sa`) with a custom role that can only read instances and set their labels, instead of the
  default compute account (Editor on the project). No key is created; the VM gets tokens from the metadata
  server. The pipeline cannot label the VM itself without a GCP credential in GitHub.
- **Reaching the VM from CI: a self-hosted GitHub Actions runner on the VM.** The runner connects *out* to
  GitHub and long-polls for jobs, so no SSH key or GCP credential is stored in GitHub, and the same design
  works for a VM with no public IP at all (e.g. a local VM). Deploy, Verify and Rollback run on it (`runs-on: [self-hosted, rf-vm]`); CI and Build &
  Publish stay on GitHub-hosted runners. Alternatives: SSH from a GitHub-hosted runner needs an SSH key (or
  a GCP service-account key) as a repository secret and port 22 open to GitHub's whole IP range; a
  pull-based agent (Watchtower) deploys whatever is pushed, so the pipeline could not run Verify against the
  exact deployment or fail on it.
- **Security of the runner**: the repository is public, and a self-hosted runner executes job code. Only
  jobs gated to pushes on `main` and manual runs target it; pull requests only run CI on GitHub-hosted
  runners. The runner user may use Docker (effectively root on the VM) but has no sudo. For a real system:
  an ephemeral runner or a private repository.
- **Deploy** (`deploy/deploy.sh <tag>`): copies the compose file and nginx config from the checkout to
  `/opt/rf-alert-manager`, sets `IMAGE_TAG` in its `.env`, `docker compose pull` and `up -d --wait`, so the
  step fails if a container does not become healthy. Exactly the `:<sha>` images CI built are deployed.
- **Rollback**: automatic when Deploy (unhealthy) or Verify (smoke test) fails: `deploy.sh --previous`
  returns to the tag recorded in `previous-tag`. Manual: the Rollback workflow, with an empty version (the
  previous deployment) or any published tag; every build stays in GHCR, so nothing is rebuilt. Rollback
  uses the current compose file and does not undo database migrations, so a migration must stay compatible
  with the previous version (additive only).
- **Start on boot**: Docker is enabled and every container has `restart: unless-stopped`; the runner is a
  systemd service. **Ports**: ufw allows only 22 and 80, and only nginx publishes a port. **Logs**: Docker
  `json-file` 10 MB × 3 per container (daemon default and compose), journald capped at 200 MB. The Google
  Cloud Ops Agent tails the container log files into Cloud Logging (tagged with the compose service name)
  and sends host metrics, so logs are searchable without SSH and outlive the local rotation. It costs about
  150 MB of RAM; the service account gets only Logs Writer and Monitoring Metric Writer.
- **Deployment is not zero-downtime**: `up -d` recreates the services, so requests fail for the few
  seconds a Spring Boot service takes to start. Sensors would need to retry.
- **Manual steps**: a GCP project with billing and a signed-in gcloud, and a runner registration token from
  GitHub (valid one hour) passed to `deploy/gcp-vm.sh`. Everything else is code.

## Not production-ready

- **Event loss window**: if publishing to RabbitMQ fails after observations are stored, that match is lost
  (logged). Fix: a transactional outbox.
- **Rule-engine state is in memory**: windows are lost on restart, and running two ingest-service replicas
  would split them. Fix: keep windows in Redis/Postgres, or partition by signal.
- **No dead-letter queue**: a message that fails all retries is only logged.
- **`GET /sensors` scans `observations`**; at volume this needs a maintained sensors table.
- **No retention** for observations or alert observation copies.
- **No authentication/authorization** and no TLS at nginx.
- **No custom metrics** (only Spring's built-ins), no dashboards or alerting on the system itself.
- One Postgres user for both schemas; no backups.
- Auto-resolve depends on the host clock being roughly in sync with sensor clocks.

## With more time

1. Zero-downtime deployment (two replicas per service behind nginx, rolled one at a time); an ephemeral
   runner.
2. Transactional outbox for match events; dead-letter queue.
3. Persist rule-engine windows so ingest-service is stateless and scalable.
4. `multi_sensor` and `sensor_silence` rules.
5. Prometheus + Grafana with a provisioned dashboard, and business metrics (observations accepted/rejected,
   matches, alerts opened/resolved).
6. Event-time auto-resolve via a watermark published by ingest-service.
7. Retention policies, auth, TLS, separate DB users per service.
