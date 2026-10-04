package com.openclaw.kbbridge.unified;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Statement;
import java.util.*;
import java.util.function.Supplier;

@Component
public class UnifiedRepository {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    public UnifiedRepository(JdbcTemplate db,PlatformTransactionManager manager) {this.db=db;this.tx=new TransactionTemplate(manager);}
    public <T> T transaction(Supplier<T> work) {return tx.execute(status->work.get());}
    public Map<String,Object> one(String sql,Object...args) {
        var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();
    }
    public java.time.Instant now() { return Objects.requireNonNull(db.queryForObject("SELECT CURRENT_TIMESTAMP",java.sql.Timestamp.class)).toInstant(); }
    public List<Map<String,Object>> rows(String sql,Object...args) {return db.queryForList(sql,args);}
    public int update(String sql,Object...args) {return db.update(sql,args);}
    public long insertTask(Object...args) {
        var holder=new GeneratedKeyHolder();
        db.update(connection->{
            var statement=connection.prepareStatement(
                "INSERT INTO kb_ingest_task(request_id,source_channel,source_type,user_id,status,review_status,content_hash,document_id,release_id,operation,source_rev_no,publish_seq,payload_json,request_fingerprint,raw_object_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                new String[]{"id"});
            for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
            return statement;
        },holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    public static String text(Map<String,Object> row,String field) {Object v=row.get(field);return v==null?null:v.toString();}
    public static long number(Map<String,Object> row,String field) {Object v=row.get(field);return v==null?0:((Number)v).longValue();}
    public static boolean flag(Map<String,Object> row,String field) {Object v=row.get(field);return Boolean.TRUE.equals(v)||(v instanceof Number n&&n.intValue()!=0);}
}
