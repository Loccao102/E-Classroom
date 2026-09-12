package com.eclassroom.core.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;import java.util.UUID;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;private final JsonMapper json;public AuditService(JdbcTemplate jdbc,JsonMapper json){this.jdbc=jdbc;this.json=json;}
    public void append(UUID schoolId,UUID actor,String action,String entityType,UUID entityId,Object oldValues,Object newValues,String reason){
        try{String oldJson=oldValues==null?null:json.writeValueAsString(oldValues);String newJson=newValues==null?null:json.writeValueAsString(newValues);jdbc.update("INSERT INTO audit.audit_entries(id,school_id,actor_user_id,action,entity_type,entity_id,old_values,new_values,reason) VALUES (?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)",UUID.randomUUID(),schoolId,actor,action,entityType,entityId,oldJson,newJson,reason);}catch(Exception e){throw new IllegalStateException("Audit serialization failed",e);}
    }
    public java.util.List<Map<String,Object>> entity(UUID schoolId,String type,UUID id){return jdbc.queryForList("SELECT id,actor_user_id,action,old_values,new_values,reason,created_at FROM audit.audit_entries WHERE school_id=? AND entity_type=? AND entity_id=? ORDER BY created_at DESC",schoolId,type,id);}
}
