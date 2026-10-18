package io.github.sunjirapath.ssg.model;

/** A raw fact found in one service by one extractor, before linking. */
public sealed interface Fact {

    /** An HTTP endpoint the service serves. {@code method} is {@code *} when any method matches. */
    record Endpoint(String method, Value path, Evidence evidence, String source) implements Fact {}

    /**
     * An outbound HTTP call. Load-balanced targets (Feign names, {@code lb://}) are normalized
     * into the URL as {@code lb://name/path}.
     */
    record Call(String client, String method, Value url, Evidence evidence) implements Fact {}

    /** A Kafka topic or Cloud Stream destination this service produces to or consumes from. */
    record TopicUse(boolean produce, Value topic, Evidence evidence, String via) implements Fact {}

    /** A table or collection this service creates in a migration ({@code owns}) or maps ({@code uses}). */
    record TableUse(boolean owns, Value table, String store, Evidence evidence, String via) implements Fact {}

    /** Something the extractor could not handle; shown as a finding. */
    record Problem(String kind, String message, Evidence evidence) implements Fact {}
}
