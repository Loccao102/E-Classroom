package com.eclassroom.core.integration;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class BulkImportProcessor {
    private final BulkImportService imports;
    public BulkImportProcessor(BulkImportService imports){this.imports=imports;}
    @Async public void processAsync(UUID jobId){imports.process(jobId);}
}
