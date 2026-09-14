package com.eclassroom.core.integration;

import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class BulkImportJobService {
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final JsonMapper json;
    private final BulkImportParser parser;
    private final BulkImportValidator validator;

    public BulkImportJobService(JdbcTemplate jdbc, AccessService access, JsonMapper json,
                                BulkImportParser parser, BulkImportValidator validator) {
        this.jdbc=jdbc; this.access=access; this.json=json; this.parser=parser; this.validator=validator;
    }

    @Transactional
    public StageResult stage(UUID schoolId, UUID actor, String type, String idempotencyKey, MultipartFile file) {
        access.requireAnyRole(schoolId, actor, "SCHOOL_ADMIN");
        String importType=validator.type(type);
        if(idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>128)
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY","Idempotency key is required and must be at most 128 characters");
        if(file==null||file.isEmpty())throw ApiException.badRequest("IMPORT_FILE_REQUIRED","Import file is required");
        if(file.getSize()>MAX_FILE_BYTES)throw ApiException.badRequest("IMPORT_FILE_TOO_LARGE","Import file must be 5 MB or smaller");
        String fileName=file.getOriginalFilename()==null?"import":file.getOriginalFilename();
        String format=format(fileName); byte[] bytes;
        try{bytes=file.getBytes();}catch(Exception ex){throw ApiException.badRequest("IMPORT_FILE_READ_FAILED","Cannot read import file");}
        String digest=sha256(bytes);
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT id,status,source_sha256,import_type FROM integration.import_jobs WHERE school_id=? AND idempotency_key=?",schoolId,idempotencyKey.trim());
        if(!existing.isEmpty()){
            Map<String,Object> row=existing.getFirst();
            if(!digest.equals(row.get("source_sha256"))||!importType.equals(row.get("import_type")))
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","Idempotency key already belongs to another import payload");
            return new StageResult((UUID)row.get("id"),String.valueOf(row.get("status")),true);
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO integration.import_jobs(id,school_id,import_type,source_format,source_file_name,source_sha256,idempotency_key,status,source_blob,created_by) VALUES (?,?,?,?,?,?,?,'QUEUED',?,?)",id,schoolId,importType,format,safeFileName(fileName),digest,idempotencyKey.trim(),bytes,actor);
        return new StageResult(id,"QUEUED",false);
    }

    @Transactional
    public void process(UUID jobId){
        List<JobSource> jobs=jdbc.query("SELECT school_id,import_type,source_format,source_blob,status FROM integration.import_jobs WHERE id=? FOR UPDATE",(rs,i)->new JobSource(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getBytes(4),rs.getString(5)),jobId);
        if(jobs.isEmpty())return; JobSource job=jobs.getFirst(); if(!List.of("QUEUED","PROCESSING").contains(job.status()))return;
        jdbc.update("UPDATE integration.import_jobs SET status='PROCESSING',updated_at=NOW(),error_message=NULL WHERE id=?",jobId);
        try{
            List<BulkImportParser.RawRow> rows=parser.parse(job.format(),job.bytes()); jdbc.update("DELETE FROM integration.import_rows WHERE job_id=?",jobId);
            int valid=0,invalid=0;
            for(BulkImportParser.RawRow row:rows){BulkImportValidator.Validation v=validator.validate(job.schoolId(),job.type(),row.values());boolean ok=v.errors().isEmpty();if(ok)valid++;else invalid++;jdbc.update("INSERT INTO integration.import_rows(id,school_id,job_id,row_number,raw_data,normalized_data,status,errors,warnings) VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb))",UUID.randomUUID(),job.schoolId(),jobId,row.rowNumber(),write(row.values()),write(v.normalized()),ok?"VALID":"INVALID",write(v.errors()),write(v.warnings()));}
            jdbc.update("UPDATE integration.import_jobs SET status=?,source_blob=NULL,total_rows=?,valid_rows=?,invalid_rows=?,version=version+1,updated_at=NOW() WHERE id=?",invalid==0?"PREVIEW_READY":"VALIDATION_FAILED",rows.size(),valid,invalid,jobId);
        }catch(Exception ex){jdbc.update("UPDATE integration.import_jobs SET status='FAILED',source_blob=NULL,error_message=?,version=version+1,updated_at=NOW() WHERE id=?",trim(ex.getMessage()),jobId);}
    }

    public ImportJobView job(UUID schoolId,UUID actor,UUID jobId){access.requireAnyRole(schoolId,actor,"SCHOOL_ADMIN");List<ImportJobView> rows=jdbc.query("SELECT id,import_type,source_format,source_file_name,status,total_rows,valid_rows,invalid_rows,error_message,version,created_at,committed_at FROM integration.import_jobs WHERE school_id=? AND id=?",(rs,i)->new ImportJobView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getString(9),rs.getLong(10),rs.getObject(11,OffsetDateTime.class),rs.getObject(12,OffsetDateTime.class)),schoolId,jobId);if(rows.isEmpty())throw ApiException.notFound("Import job was not found");return rows.getFirst();}
    public List<ImportJobView> jobs(UUID schoolId,UUID actor){access.requireAnyRole(schoolId,actor,"SCHOOL_ADMIN");return jdbc.query("SELECT id,import_type,source_format,source_file_name,status,total_rows,valid_rows,invalid_rows,error_message,version,created_at,committed_at FROM integration.import_jobs WHERE school_id=? ORDER BY created_at DESC,id DESC LIMIT 100",(rs,i)->new ImportJobView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getString(9),rs.getLong(10),rs.getObject(11,OffsetDateTime.class),rs.getObject(12,OffsetDateTime.class)),schoolId);}
    public List<ImportRowView> preview(UUID schoolId,UUID actor,UUID jobId){job(schoolId,actor,jobId);return jdbc.query("SELECT row_number,raw_data::text,normalized_data::text,status,errors::text,warnings::text,committed_entity_id FROM integration.import_rows WHERE school_id=? AND job_id=? ORDER BY row_number LIMIT 500",(rs,i)->new ImportRowView(rs.getInt(1),readMap(rs.getString(2)),readMap(rs.getString(3)),rs.getString(4),readList(rs.getString(5)),readList(rs.getString(6)),rs.getObject(7,UUID.class)),schoolId,jobId);}

    public List<String> templateHeaders(String type){return validator.headers(type);}
    private String format(String name){String n=name.toLowerCase(Locale.ROOT);if(n.endsWith(".csv"))return"CSV";if(n.endsWith(".xlsx"))return"XLSX";throw ApiException.badRequest("UNSUPPORTED_IMPORT_FORMAT","Only .csv and .xlsx files are supported");}
    private String safeFileName(String name){String n=name.replaceAll("[\\r\\n\\t]","_");return n.length()>255?n.substring(n.length()-255):n;}
    private String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Map<String,Object> readMap(String value){try{return json.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception ex){throw new IllegalStateException(ex);}}
    private List<String> readList(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String trim(String value){if(value==null||value.isBlank())return"Import processing failed";return value.length()>2000?value.substring(0,2000):value;}

    public record StageResult(UUID jobId,String status,boolean idempotentReplay){}
    public record ImportJobView(UUID id,String importType,String sourceFormat,String sourceFileName,String status,int totalRows,int validRows,int invalidRows,String errorMessage,long version,OffsetDateTime createdAt,OffsetDateTime committedAt){}
    public record ImportRowView(int rowNumber,Map<String,Object> rawData,Map<String,Object> normalizedData,String status,List<String> errors,List<String> warnings,UUID committedEntityId){}
    private record JobSource(UUID schoolId,String type,String format,byte[] bytes,String status){}
}
