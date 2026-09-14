package com.tracer.gateway;

public class LogEntry {
    public String traceId;
    public String serviceName;
    public String message;
    public String status;
    public long timestamp;

    public LogEntry() {}

    public LogEntry(String traceId, String serviceName, String message, String status, long timestamp) {
        this.traceId = traceId;
        this.serviceName = serviceName;
        this.message = message;
        this.status = status;
        this.timestamp = timestamp;
    }
}
