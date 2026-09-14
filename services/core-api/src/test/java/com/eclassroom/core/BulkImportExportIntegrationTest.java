package com.eclassroom.core;

import com.eclassroom.core.academic.BulkAcademicImportWriter;
import com.eclassroom.core.audit.AuditService;
import com.eclassroom.core.identity.AccessService;
import com.eclassroom.core.integration.*;
import com.eclassroom.core.shared.api.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class BulkImportExportIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_bulk_test").withUsername("eclassroom").withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static BulkImportJobService jobs;
    static BulkImportCommitService commits;
    static BulkExportService exports;

    @BeforeAll
    static void setup(){
        PGSimpleDataSource ds=new PGSimpleDataSource();ds.setURL(POSTGRES.getJdbcUrl());ds.setUser(POSTGRES.getUsername());ds.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(ds);JsonMapper json=JsonMapper.builder().build();AccessService access=new AccessService(jdbc);AuditService audit=new AuditService(jdbc,json);
        BulkImportParser parser=new BulkImportParser();BulkImportValidator validator=new BulkImportValidator(jdbc);
        jobs=new BulkImportJobService(jdbc,access,json,parser,validator);
        commits=new BulkImportCommitService(jdbc,access,json,audit,new BulkAcademicImportWriter(jdbc));
        exports=new BulkExportService(jdbc,access,new TabularExportWriter(),json);
    }

    @Test
    void partialInvalidPreviewDoesNotMutateAndIdempotencyIsDeterministic(){
        Fixture f=fixture("PARTIAL");
        byte[] csv=("student_code,full_name,date_of_birth,email\nHS001,Student One,2010-01-02,one@example.com\nHS002,,2010-02-03,two@example.com\n").getBytes(StandardCharsets.UTF_8);
        var staged=jobs.stage(f.school(),f.admin(),"STUDENT","partial-1",file("students.csv",csv));jobs.process(staged.jobId());
        var job=jobs.job(f.school(),f.admin(),staged.jobId());
        assertEquals("VALIDATION_FAILED",job.status());assertEquals(2,job.totalRows());assertEquals(1,job.validRows());assertEquals(1,job.invalidRows());
        assertEquals(0,count("SELECT COUNT(*) FROM academic.students WHERE school_id=?",f.school()));
        assertTrue(jobs.preview(f.school(),f.admin(),staged.jobId()).stream().anyMatch(row->"INVALID".equals(row.status())));
        var replay=jobs.stage(f.school(),f.admin(),"STUDENT","partial-1",file("students.csv",csv));
        assertEquals(staged.jobId(),replay.jobId());assertTrue(replay.idempotentReplay());
        ApiException reused=assertThrows(ApiException.class,()->jobs.stage(f.school(),f.admin(),"STUDENT","partial-1",file("students.csv","student_code,full_name\nX,X\n".getBytes(StandardCharsets.UTF_8))));
        assertEquals("IDEMPOTENCY_KEY_REUSED",reused.code());
    }

    @Test
    void cleanPreviewCommitsOnceAndReplayDoesNotDuplicate(){
        Fixture f=fixture("COMMIT");
        byte[] csv=("student_code,full_name,date_of_birth,gender,email\nHS101,Student A,2010-01-02,F,a@example.com\nHS102,Student B,2010-02-03,M,b@example.com\n").getBytes(StandardCharsets.UTF_8);
        var staged=jobs.stage(f.school(),f.admin(),"STUDENT","clean-1",file("students.csv",csv));jobs.process(staged.jobId());
        var ready=jobs.job(f.school(),f.admin(),staged.jobId());assertEquals("PREVIEW_READY",ready.status());
        var result=commits.commit(f.school(),f.admin(),staged.jobId(),ready.version());assertEquals(2,result.committedRows());assertFalse(result.idempotentReplay());
        assertEquals(2,count("SELECT COUNT(*) FROM academic.students WHERE school_id=?",f.school()));
        var replay=commits.commit(f.school(),f.admin(),staged.jobId(),ready.version());assertTrue(replay.idempotentReplay());
        assertEquals(2,count("SELECT COUNT(*) FROM academic.students WHERE school_id=?",f.school()));
        assertEquals(2,count("SELECT COUNT(*) FROM integration.import_rows WHERE job_id=? AND status='COMMITTED'",staged.jobId()));
    }

    @Test
    void enrollmentReferenceCannotCrossTenantBoundary(){
        Fixture a=fixture("TENANTA");Fixture b=fixture("TENANTB");
        UUID student=UUID.randomUUID();jdbc.update("INSERT INTO academic.students(id,school_id,student_code,full_name,status) VALUES (?,?,?,'Student','ACTIVE')",student,a.school(),"HS500");
        UUID year=UUID.randomUUID();jdbc.update("INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,? ,?,'ACTIVE')",year,b.school(),"2026-2027",LocalDate.of(2026,8,1),LocalDate.of(2027,6,30));
        UUID classroom=UUID.randomUUID();jdbc.update("INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,status) VALUES (?,?,?,?,?,'ACTIVE')",classroom,b.school(),year,"10A1","10A1");
        byte[] csv="student_code,classroom_code,academic_year,start_date\nHS500,10A1,2026-2027,2026-08-01\n".getBytes(StandardCharsets.UTF_8);
        var staged=jobs.stage(a.school(),a.admin(),"ENROLLMENT","tenant-ref",file("enrollment.csv",csv));jobs.process(staged.jobId());
        var job=jobs.job(a.school(),a.admin(),staged.jobId());assertEquals("VALIDATION_FAILED",job.status());assertEquals(1,job.invalidRows());
    }

    @Test
    void peopleExportIsAuthorizedAndAudited(){
        Fixture f=fixture("EXPORT");UUID student=UUID.randomUUID();jdbc.update("INSERT INTO academic.students(id,school_id,student_code,full_name,email,status) VALUES (?,?,?,?,?,'ACTIVE')",student,f.school(),"HS900","Export Student","export@example.com");
        var file=exports.export(f.school(),f.admin(),"STUDENTS","CSV",null,null,null);
        assertEquals(1,file.rowCount());assertTrue(new String(file.bytes(),StandardCharsets.UTF_8).contains("HS900"));
        assertEquals(1,count("SELECT COUNT(*) FROM integration.export_audit WHERE school_id=? AND export_type='STUDENTS'",f.school()));
        UUID outsider=user("outsider",null,null);assertThrows(ApiException.class,()->exports.export(f.school(),outsider,"STUDENTS","CSV",null,null,null));
    }

    private static Fixture fixture(String prefix){UUID school=UUID.randomUUID();jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)",school,prefix+school.toString().substring(0,6),prefix+" School");UUID admin=user(prefix.toLowerCase(),school,"SCHOOL_ADMIN");return new Fixture(school,admin);}
    private static UUID user(String prefix,UUID school,String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",id,prefix+id+"@example.com","unused",prefix);if(school!=null)jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')",id,school,role);return id;}
    private static MockMultipartFile file(String name,byte[] bytes){return new MockMultipartFile("file",name,"text/csv",bytes);}
    private static int count(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    private record Fixture(UUID school,UUID admin){}
}
