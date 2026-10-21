# RF Alert Manager

## Simulator

`simulator.sh` sends realistic RF traffic to ingest-service and prints the resulting alerts every 10 s.
It needs only `bash`, `curl` and `awk`.

```bash
./simulator.sh                                   # all scenarios against localhost:8081 / :8082, 180 s
./simulator.sh -u http://<vm> -a http://<vm>     # through the reverse proxy
./simulator.sh -s persistent,burst -r 2 -d 60    # chosen scenarios, 2 obs/s per sensor, 60 s
./simulator.sh -h                                # all options
```

| Option | Env var | Default | Meaning |
|---|---|---|---|
| `-u` | `INGEST_URL` | `http://localhost:8081` | ingest-service base URL |
| `-a` | `ALERTS_URL` | `http://localhost:8082` | alert-service base URL (`""` skips alert status) |
| `-r` | `RATE` | `1` | observations per second per sensor (fractions allowed) |
| `-d` | `DURATION` | `180` | run time in seconds |
| `-s` | `SCENARIOS` | all four | comma-separated scenarios, see below |

| Scenario | Traffic | Expected result |
|---|---|---|
| `background` | 5 sensors on 433/868/915 MHz, 5 GHz and weak 2.4 GHz Wi-Fi (below -50 dBm), whole run | no alert |
| `persistent` | 2437 MHz at about -40 dBm on sensor-03, from 10 s to the end | `power_threshold` alert opens after 10 s and stays open |
| `burst` | 2462 MHz at about -35 dBm on sensor-01, 20 s to 23 s | no alert (shorter than `min_duration_s`) |
| `disappearing` | 2452 MHz at about -43 dBm on sensor-04, 10 s to 70 s | alert opens, then auto-resolves around 140 s (`resolve_after_s` 60 + 10 s grace) |

Phase boundaries can be moved with `PERSISTENT_START`, `BURST_START`, `BURST_LENGTH`,
`DISAPPEAR_START` and `DISAPPEAR_STOP`.
