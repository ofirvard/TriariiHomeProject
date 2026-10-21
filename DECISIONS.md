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
  PRs run CI only; merges to `main` (or a manual run) run CI → Build & Publish → Verify.
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
- Secrets: the pipeline uses only the built-in `GITHUB_TOKEN`; Verify generates throwaway passwords.

## VM deployment: not done

A local Ubuntu 24.04 VM (Vagrant + VirtualBox, since Windows Home has no Hyper-V) with a self-hosted GitHub
Actions runner was designed and started: the runner connects *out* to GitHub, which solves "the CI runner
cannot reach a local VM" without opening ports or storing SSH keys, and Deploy/Verify/Rollback would run on
it. It was dropped for time. Consequences:

- **Deploy stage is missing.** Verify runs the exact published images as a full production-like stack on
  the CI runner and smoke-tests that, instead of a deployed service.
- **Rollback** is not automated; every version stays in GHCR (`:<sha>`, `:v0.N.0`), so returning to a
  previous version means running the stack with that tag (`IMAGE_TAG=v0.1.0 docker compose up -d`).
- VM provisioning as code, start on boot (Docker enabled + `restart: unless-stopped`), firewall (22/80
  only) and host-level log limits were part of the design but are not in the repository.

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

1. Finish the VM: Vagrant + provisioning script, self-hosted runner, Deploy stage, Verify against the VM,
   one-click Rollback workflow taking a version.
2. Transactional outbox for match events; dead-letter queue.
3. Persist rule-engine windows so ingest-service is stateless and scalable.
4. `multi_sensor` and `sensor_silence` rules.
5. Prometheus + Grafana with a provisioned dashboard, and business metrics (observations accepted/rejected,
   matches, alerts opened/resolved).
6. Event-time auto-resolve via a watermark published by ingest-service.
7. Retention policies, auth, TLS, separate DB users per service.
