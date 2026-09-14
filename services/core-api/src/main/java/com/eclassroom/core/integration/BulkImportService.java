package com.eclassroom.core.integration;

import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class BulkImportService {
    private static final Set<String> TYPES=Set.of("STUDENT","TEACHER","GUARDIAN","GUARDIAN_LINK","ENROLLMENT");
    private final JdbcTemplate jdbc; private final AccessService access; private final TabularFileService tabular; private final ImportValidationService validator; private final JsonMapper json; private final AuditService audit;
    public BulkImportService(JdbcTemplate jdbc,AccessService access,TabularFileService tabular,ImportValidationService validator,JsonMapper json,AuditService audit){this.jdbc=jdbc;this.access=access;this.tabular=tabular;this.validator=validator;this.json=json;this.audit=audit;}

    public JobView stage(UUID schoolId,UUID actor,String requestedType,String fileName,String idempotencyKey,byte[] bytes){
        access.requireAnyRole(schoolId,actor,"SCHOOL_ADMIN"); String type=normalizeType(requestedType); String format=format(fileName); String hash=sha256(bytes);
        String key=idempotencyKey==null||idempotencyKey.isBlank()?type+":"+hash:idempotencyKey.trim(); if(key.length()>128)throw ApiException.badRequest("IDEMPOTENCY_KEY_TOO_LONG","Idempotency key is limited to 128 characters");
        List<JobView> previous=jdbc.query("SELECT * FROM integration.import_jobs WHERE school_id=? AND idempotency_key=?",this::mapJob,schoolId,key);
        if(!previous.isEmpty()){JobView job=previous.getFirst();if(!job.sourceSha256().equals(hash)||!job.importType().equals(type))throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED","Idempotency key was already used for different import content");return job;}
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO integration.import_jobs(id,school_id,import_type,source_format,source_file_name,source_sha256,idempotency_key,status,source_blob,created_by) VALUES (?,?,?,?,?,?,?,'QUEUED',?,?)",id,schoolId,type,format,safeName(fileName),hash,key,bytes,actor);
        audit.append(schoolId,actor,"STAGE","IMPORT_JOB",id,null,Map.of("importType",type,"sourceFormat",format,"sourceSha256",hash),null);return requireJob(id);
    }

    public void process(UUID jobId){
        int claimed=jdbc.update("UPDATE integration.import_jobs SET status='PROCESSING',updated_at=NOW(),version=version+1 WHERE id=? AND status='QUEUED'",jobId);if(claimed!=1)return;
        try{
            Map<String,Object> source=jdbc.queryForMap("SELECT school_id,import_type,source_format,source_blob FROM integration.import_jobs WHERE id=?",jobId);UUID school=(UUID)source.get("school_id");String type=String.valueOf(source.get("import_type"));byte[] bytes=(byte[])source.get("source_blob");
            jdbc.update("DELETE FROM integration.import_rows WHERE job_id=?",jobId);int[] counts={0,0,0};Set<String> seen=new HashSet<>();
            TabularFileService.ParseSummary summary=tabular.parse(String.valueOf(source.get("source_format")),bytes,(rowNumber,raw)->{ImportValidationService.ValidationResult result=validator.validate(school,type,raw,seen);insertRow(school,jobId,rowNumber,raw,result);counts[0]++;if(result.valid())counts[1]++;else counts[2]++;});
            validator.validateHeaders(type,summary.headers());String status=counts[2]==0?"PREVIEW_READY":"VALIDATION_FAILED";
            jdbc.update("UPDATE integration.import_jobs SET status=?,total_rows=?,valid_rows=?,invalid_rows=?,source_blob=NULL,error_message=NULL,updated_at=NOW(),version=version+1 WHERE id=?",status,counts[0],counts[1],counts[2],jobId);
        }catch(Exception ex){jdbc.update("UPDATE integration.import_jobs SET status='FAILED',source_blob=NULL,error_message=?,updated_at=NOW(),version=version+1 WHERE id=?",limit(ex.getMessage(),2000),jobId);}
    }

    public List<JobView> jobs(UUID schoolId,UUID actor){access.requireAnyRole(schoolId,actor,"SCHOOL_ADMIN");return jdbc.query("SELECT * FROM integration.import_jobs WHERE school_id=? ORDER BY created_at DESC,id DESC LIMIT 100",this::mapJob,schoolId);}
    public JobView job(UUID jobId,UUID actor){JobView job=requireJob(jobId);access.requireAnyRole(job.schoolId(),actor,"SCHOOL_ADMIN");return job;}
    public List<RowView> rows(UUID jobId,UUID actor,String status,int limit,int offset){JobView job=job(jobId,actor);int bounded=Math.max(1,Math.min(limit,200));int start=Math.max(0,offset);String normalized=status==null?"":status.trim().toUpperCase(Locale.ROOT);if(!normalized.isBlank()&&!Set.of("VALID","INVALID","COMMITTED").contains(normalized))throw ApiException.badRequest("IMPORT_ROW_STATUS_INVALID","Unsupported row status");String sql="SELECT row_number,raw_data::text raw_json,normalized_data::text normalized_json,status,errors::text errors_json,warnings::text warnings_json,committed_entity_id FROM integration.import_rows WHERE job_id=?"+(normalized.isBlank()?"":" AND status=?")+" ORDER BY row_number LIMIT ? OFFSET ?";Object[] args=normalized.isBlank()?new Object[]{job.id(),bounded,start}:new Object[]{job.id(),normalized,bounded,start};return jdbc.query(sql,(rs,i)->new RowView(rs.getInt("row_number"),read(rs.getString("raw_json")),read(rs.getString("normalized_json")),rs.getString("status"),read(rs.getString("errors_json")),read(rs.getString("warnings_json")),rs.getObject("committed_entity_id",UUID.class)),args);}

    private void insertRow(UUID school,UUID jobId,int rowNumber,Map<String,String> raw,ImportValidationService.ValidationResult result)throws Exception{jdbc.update("INSERT INTO integration.import_rows(id,school_id,job_id,row_number,raw_data,normalized_data,status,errors,warnings) VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb))",UUID.randomUUID(),school,jobId,rowNumber,json.writeValueAsString(raw),json.writeValueAsString(result.normalized()),result.valid()?"VALID":"INVALID",json.writeValueAsString(result.errors()),json.writeValueAsString(result.warnings()));}
    JobView requireJob(UUID id){List<JobView> rows=jdbc.query("SELECT * FROM integration.import_jobs WHERE id=?",this::mapJob,id);if(rows.isEmpty())throw ApiException.notFound("Import job was not found");return rows.getFirst();}
    private JobView mapJob(java.sql.ResultSet rs,int i)throws java.sql.SQLException{return new JobView(UUID.fromString(rs.getString("id")),UUID.fromString(rs.getString("school_id")),rs.getString("import_type"),rs.getString("source_format"),rs.getString("source_file_name"),rs.getString("source_sha256"),rs.getString("idempotency_key"),rs.getString("status"),rs.getInt("total_rows"),rs.getInt("valid_rows"),rs.getInt("invalid_rows"),rs.getLong("version"),rs.getString("error_message"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("committed_at",OffsetDateTime.class));}
    private Object read(String value){if(value==null)return null;try{return json.readValue(value,Object.class);}catch(Exception ex){return value;}}
    private String normalizeType(String value){String type=value==null?"":value.trim().toUpperCase(Locale.ROOT);if(!TYPES.contains(type))throw ApiException.badRequest("IMPORT_TYPE_UNSUPPORTED","Unsupported import type");return type;}
    private String format(String fileName){String name=fileName==null?"":fileName.toLowerCase(Locale.ROOT);if(name.endsWith(".csv"))return"CSV";if(name.endsWith(".xlsx"))return"XLSX";throw ApiException.badRequest("IMPORT_FORMAT_UNSUPPORTED","Upload a .csv or .xlsx file");}
    private String safeName(String value){String name=value==null?"import.csv":value.replace('\\','/');int slash=name.lastIndexOf('/');name=slash>=0?name.substring(slash+1):name;return name.length()>255?name.substring(name.length()-255):name;}
    private String sha256(byte[] bytes){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);return HexFormat.of().formatHex(digest);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String limit(String value,int max){String text=value==null?"Import processing failed":value;return text.length()<=max?text:text.substring(0,max);}

    public record JobView(UUID id,UUID schoolId,String importType,String sourceFormat,String sourceFileName,String sourceSha256,String idempotencyKey,String status,int totalRows,int validRows,int invalidRows,long version,String errorMessage,OffsetDateTime createdAt,OffsetDateTime committedAt){}
    public record RowView(int rowNumber,Object rawData,Object normalizedData,String status,Object errors,Object warnings,UUID committedEntityId){}
}
