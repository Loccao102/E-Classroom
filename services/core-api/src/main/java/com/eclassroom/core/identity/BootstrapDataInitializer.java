package com.eclassroom.core.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class BootstrapDataInitializer implements CommandLineRunner {
    private final JdbcTemplate jdbc; private final PasswordEncoder passwords;
    @Value("${app.bootstrap.email}") String email; @Value("${app.bootstrap.password}") String password;
    @Value("${app.bootstrap.full-name}") String fullName; @Value("${app.bootstrap.school-code}") String schoolCode; @Value("${app.bootstrap.school-name}") String schoolName;
    public BootstrapDataInitializer(JdbcTemplate jdbc, PasswordEncoder passwords) { this.jdbc=jdbc; this.passwords=passwords; }
    @Override public void run(String... args) {
        if (email==null || email.isBlank() || password==null || password.isBlank()) return;
        UUID userId = findId("SELECT id FROM identity.users WHERE lower(email)=lower(?)", email);
        if (userId==null) { userId=UUID.randomUUID(); jdbc.update("INSERT INTO identity.users(id,email,password_hash,full_name,platform_role) VALUES (?,?,?,?,?)", userId,email.toLowerCase(),passwords.encode(password),fullName,"SUPER_ADMIN"); }
        UUID schoolId = findId("SELECT id FROM school.schools WHERE code=?", schoolCode.toUpperCase());
        if (schoolId==null) { schoolId=UUID.randomUUID(); jdbc.update("INSERT INTO school.schools(id,code,name,timezone,status) VALUES (?,?,?,?,?)", schoolId,schoolCode.toUpperCase(),schoolName,"Asia/Bangkok","ACTIVE"); }
        jdbc.update("INSERT INTO identity.school_memberships(user_id,school_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING", userId,schoolId,"SCHOOL_ADMIN");
    }
    private UUID findId(String sql,Object arg) { List<UUID> ids=jdbc.query(sql,(rs,i)->UUID.fromString(rs.getString(1)),arg); return ids.isEmpty()?null:ids.getFirst(); }
}
