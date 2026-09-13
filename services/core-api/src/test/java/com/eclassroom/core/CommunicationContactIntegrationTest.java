package com.eclassroom.core;

import com.eclassroom.core.communication.CommunicationContactService;
import com.eclassroom.core.identity.AccessService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class CommunicationContactIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("eclassroom_contacts_test")
            .withUsername("eclassroom")
            .withPassword("eclassroom");

    static JdbcTemplate jdbc;
    static CommunicationContactService contacts;

    @BeforeAll
    static void setUpDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        contacts = new CommunicationContactService(jdbc, new AccessService(jdbc));
    }

    @Test
    void roleContactsAreRelationshipScoped() {
        UUID schoolId = school();
        UUID admin = user("admin", schoolId, "SCHOOL_ADMIN");
        UUID parent = user("parent", schoolId, "PARENT");
        UUID studentUser = user("student", schoolId, "STUDENT");
        UUID relatedTeacher = user("teacher", schoolId, "TEACHER");
        UUID unrelatedTeacher = user("unrelated", schoolId, "TEACHER");

        UUID yearId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.academic_years(id,school_id,name,start_date,end_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                yearId, schoolId, "2026-2027-" + yearId.toString().substring(0, 8),
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 6, 30));

        UUID relatedTeacherId = teacher(schoolId, relatedTeacher, "Related Teacher");
        teacher(schoolId, unrelatedTeacher, "Unrelated Teacher");
        UUID classroomId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.classrooms(id,school_id,academic_year_id,code,name,homeroom_teacher_id,status) VALUES (?,?,?,?,?,?,'ACTIVE')",
                classroomId, schoolId, yearId, "10A1-" + classroomId.toString().substring(0, 8), "Class 10A1", relatedTeacherId);

        UUID studentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.students(id,school_id,user_id,student_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                studentId, schoolId, studentUser, "HS-" + studentId.toString().substring(0, 8), "Student A");
        jdbc.update(
                "INSERT INTO academic.class_enrollments(id,school_id,classroom_id,student_id,start_date,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), schoolId, classroomId, studentId, LocalDate.of(2026, 8, 1));

        UUID guardianId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.guardians(id,school_id,user_id,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                guardianId, schoolId, parent, "Parent A");
        jdbc.update(
                "INSERT INTO academic.student_guardians(school_id,student_id,guardian_id,relationship,primary_contact,notifications_enabled) " +
                        "VALUES (?,?,?,'PARENT',TRUE,TRUE)",
                schoolId, studentId, guardianId);

        UUID subjectId = UUID.randomUUID();
        jdbc.update("INSERT INTO academic.subjects(id,school_id,code,name) VALUES (?,?,?,?)",
                subjectId, schoolId, "MATH-" + subjectId.toString().substring(0, 8), "Mathematics");
        jdbc.update(
                "INSERT INTO academic.teaching_assignments(id,school_id,teacher_id,classroom_id,subject_id,status) VALUES (?,?,?,?,?,'ACTIVE')",
                UUID.randomUUID(), schoolId, relatedTeacherId, classroomId, subjectId);

        Set<UUID> parentContacts = ids(contacts.contacts(schoolId, parent));
        assertTrue(parentContacts.contains(relatedTeacher));
        assertTrue(parentContacts.contains(admin));
        assertFalse(parentContacts.contains(unrelatedTeacher));
        assertFalse(parentContacts.contains(studentUser));

        Set<UUID> teacherContacts = ids(contacts.contacts(schoolId, relatedTeacher));
        assertTrue(teacherContacts.contains(parent));
        assertTrue(teacherContacts.contains(studentUser));
        assertTrue(teacherContacts.contains(admin));
        assertFalse(teacherContacts.contains(unrelatedTeacher));

        Set<UUID> studentContacts = ids(contacts.contacts(schoolId, studentUser));
        assertTrue(studentContacts.contains(relatedTeacher));
        assertTrue(studentContacts.contains(admin));
        assertFalse(studentContacts.contains(unrelatedTeacher));

        Set<UUID> adminContacts = ids(contacts.contacts(schoolId, admin));
        assertTrue(adminContacts.contains(parent));
        assertTrue(adminContacts.contains(studentUser));
        assertTrue(adminContacts.contains(relatedTeacher));
        assertTrue(adminContacts.contains(unrelatedTeacher));
    }

    private static Set<UUID> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (UUID) row.get("user_id")).collect(Collectors.toSet());
    }

    private static UUID school() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO school.schools(id,code,name) VALUES (?,?,?)",
                id, "CONTACT-" + id.toString().substring(0, 8), "Contact School");
        return id;
    }

    private static UUID user(String prefix, UUID schoolId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity.users(id,email,password_hash,full_name,status) VALUES (?,?,?,?, 'ACTIVE')",
                id, prefix + "-" + id + "@example.com", "not-used", prefix + " user");
        jdbc.update(
                "INSERT INTO identity.school_memberships(user_id,school_id,role,status) VALUES (?,?,?,'ACTIVE')",
                id, schoolId, role);
        return id;
    }

    private static UUID teacher(UUID schoolId, UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO academic.teachers(id,school_id,user_id,teacher_code,full_name,status) VALUES (?,?,?,?,?,'ACTIVE')",
                id, schoolId, userId, "GV-" + id.toString().substring(0, 8), name);
        return id;
    }
}
