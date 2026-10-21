package com.triarii.rf.alerts.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables under the {@code rf.} prefix; each can be overridden by env var (e.g. RF_RESOLVE_GRACE=30s).
 *
 * @param resolveGrace       extra wait on top of resolve_after_s before auto-resolving, to absorb pipeline
 *                           latency (an observation that is in flight should not resolve then reopen an alert)
 * @param detailObservations max contributing observations returned by GET /alerts/{id}
 */
@ConfigurationProperties("rf")
public record AlertProperties(
        @DefaultValue("10s") Duration resolveGrace,
        @DefaultValue("100") int detailObservations) {
}
