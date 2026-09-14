package com.eclassroom.core.integration;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class BulkImportProcessor {
    private final BulkImportJobService jobs;

    public BulkImportProcessor(BulkImportJobService jobs) {
        this.jobs = jobs;
    }

    @Async
    public void processAsync(UUID jobId) {
        jobs.process(jobId);
    }
}
