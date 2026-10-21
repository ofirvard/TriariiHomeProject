package com.triarii.rf.ingest.observation;

import java.util.List;

/** Per-request outcome. Valid items are stored even when others in the same batch are rejected. */
public record IngestResult(int accepted, int rejected, List<ItemError> errors) {

    /** @param index position in the submitted batch (0 for a single object) */
    public record ItemError(int index, String field, String message) {
    }
}
