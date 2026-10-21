package com.triarii.rf.ingest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables under the {@code rf.} prefix; each can be overridden by env var (e.g. RF_MAX_GAP=10s).
 *
 * @param rulesFile              rules seeded at startup (Spring resource location)
 * @param maxBatchSize           max observations per POST
 * @param maxFutureSkew          timestamps further in the future than this are rejected (sensor clock skew)
 * @param allowedLateness        observations older than (watermark - this) are stored but not evaluated
 * @param maxGap                 a pending (not yet firing) window resets if no match arrives within this gap
 * @param bucketMhz              frequency bucket width that defines "the same signal"
 * @param maxPendingObservations cap on observations buffered per pending window
 */
@ConfigurationProperties("rf")
public record IngestProperties(
        @DefaultValue("classpath:rules.yaml") String rulesFile,
        @DefaultValue("1000") int maxBatchSize,
        @DefaultValue("60s") Duration maxFutureSkew,
        @DefaultValue("120s") Duration allowedLateness,
        @DefaultValue("5s") Duration maxGap,
        @DefaultValue("0.1") double bucketMhz,
        @DefaultValue("500") int maxPendingObservations) {
}
