package com.tracer.gateway;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Trace ID policy at the system's entry point. */
public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";

    /** Canonical 8-4-4-4-12 hex UUID. Stricter than UUID.fromString, which accepts e.g. "1-1-1-1-1". */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private TraceIds() {}

    /** Honors a well-formed inbound ID (normalized to lower case); otherwise generates a new random UUID. */
    public static String resolve(String inbound) {
        if (inbound != null && UUID_PATTERN.matcher(inbound).matches()) {
            return inbound.toLowerCase(Locale.ROOT);
        }
        return UUID.randomUUID().toString();
    }
}
