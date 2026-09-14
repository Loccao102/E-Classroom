package com.eclassroom.core.integration;

import com.eclassroom.core.shared.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class BulkDataController {
    private final BulkImportJobService imports;
    private final BulkImportProcessor processor;
    private final BulkImportCommitService commits;
    private final BulkExportService exports;

    public BulkDataController(BulkImportJobService imports, BulkImportProcessor processor,
                              BulkImportCommitService commits, BulkExportService exports) {
        this.imports=imports; this.processor=processor; this.commits=commits; this.exports=exports;
    }

    @PostMapping(value="/schools/{schoolId}/imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkImportJobService.StageResult stage(@PathVariable UUID schoolId,@RequestParam String type,
                                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                   @RequestPart("file") MultipartFile file,Authentication auth) {
        BulkImportJobService.StageResult result=imports.stage(schoolId,CurrentUser.id(auth),type,idempotencyKey,file);
        if(!result.idempotentReplay()&&"QUEUED".equals(result.status()))processor.processAsync(result.jobId());
        return result;
    }

    @GetMapping("/schools/{schoolId}/imports")
    public List<BulkImportJobService.ImportJobView> jobs(@PathVariable UUID schoolId,Authentication auth) {
        return imports.jobs(schoolId,CurrentUser.id(auth));
    }

    @GetMapping("/schools/{schoolId}/imports/{jobId}")
    public BulkImportJobService.ImportJobView job(@PathVariable UUID schoolId,@PathVariable UUID jobId,Authentication auth) {
        return imports.job(schoolId,CurrentUser.id(auth),jobId);
    }

    @GetMapping("/schools/{schoolId}/imports/{jobId}/rows")
    public List<BulkImportJobService.ImportRowView> rows(@PathVariable UUID schoolId,@PathVariable UUID jobId,Authentication auth) {
        return imports.preview(schoolId,CurrentUser.id(auth),jobId);
    }

    @PostMapping("/schools/{schoolId}/imports/{jobId}/commit")
    public BulkImportCommitService.CommitResult commit(@PathVariable UUID schoolId,@PathVariable UUID jobId,
                                                        @RequestBody CommitRequest request,Authentication auth) {
        return commits.commit(schoolId,CurrentUser.id(auth),jobId,request.version());
    }

    @GetMapping("/schools/{schoolId}/imports/templates/{type}")
    public ResponseEntity<byte[]> template(@PathVariable UUID schoolId,@PathVariable String type,
                                            @RequestParam(defaultValue="CSV") String format,Authentication auth) {
        imports.jobs(schoolId,CurrentUser.id(auth));
        return attachment(exports.template(type,format,imports.templateHeaders(type)));
    }

    @GetMapping("/schools/{schoolId}/exports/{type}")
    public ResponseEntity<byte[]> export(@PathVariable UUID schoolId,@PathVariable String type,
                                          @RequestParam(defaultValue="CSV") String format,
                                          @RequestParam(required=false) UUID classroomId,
                                          @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
                                          Authentication auth) {
        return attachment(exports.export(schoolId,CurrentUser.id(auth),type,format,classroomId,from,to));
    }

    private ResponseEntity<byte[]> attachment(BulkExportService.ExportFile file) {
        HttpHeaders headers=new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build());
        headers.setContentLength(file.bytes().length);
        return ResponseEntity.ok().headers(headers).body(file.bytes());
    }

    public record CommitRequest(long version) {}
}
