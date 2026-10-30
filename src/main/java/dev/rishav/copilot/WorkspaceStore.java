package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static dev.rishav.copilot.Models.*;

/** Single-process workspace repository. Relationships live in SQL; versioned domain snapshots remain JSON. */
@Component
public class WorkspaceStore implements AutoCloseable {
    private final HikariDataSource ds=new HikariDataSource();
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    public WorkspaceStore(ObjectMapper mapper,
            @Value("${copilot.storage-url:jdbc:h2:file:./.tools/workspace;DB_CLOSE_ON_EXIT=FALSE}") String url,
            @Value("${copilot.storage-user:sa}") String user,
            @Value("${copilot.storage-password:}") String password) throws Exception {
        this.mapper=mapper; ds.setJdbcUrl(url); ds.setUsername(user); ds.setPassword(password); ds.setMaximumPoolSize(2);
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE IF NOT EXISTS workspace_migrations (version INTEGER PRIMARY KEY)");
        if(db.queryForObject("SELECT COUNT(*) FROM workspace_migrations WHERE version=1",Integer.class)==0) {
            var sql=new ClassPathResource("db/workspace/V1.sql").getContentAsString(StandardCharsets.UTF_8);
            for(String statement:sql.split(";")) if(!statement.isBlank()) db.execute(statement);
            db.update("INSERT INTO workspace_migrations(version) VALUES(1)");
        }
    }
    private String json(Object o) { try { return mapper.writeValueAsString(o); } catch(Exception e) { throw new IllegalStateException("Could not serialize workspace record",e); } }
    private <T> T decode(String s,Class<T> type) { try { return mapper.readValue(s,type); } catch(Exception e) { throw new IllegalStateException("Could not read workspace record",e); } }
    public List<Project> projects() { return db.query("SELECT * FROM projects ORDER BY created_at",(r,i)->new Project(UUID.fromString(r.getString("id")),r.getString("name"),Instant.parse(r.getString("created_at")),r.getBoolean("deleting"))); }
    public List<SavedSpecification> specifications() { return db.query("SELECT * FROM specifications ORDER BY version",(r,i)->new SavedSpecification(UUID.fromString(r.getString("project_id")),r.getInt("version"),decode(r.getString("payload"),Specification.class),decode(r.getString("source"),ImportRequest.class))); }
    public <T> List<T> records(String table,Class<T> type) { return db.query("SELECT payload FROM "+table,(r,i)->decode(r.getString(1),type)); }
    public Map<UUID,Analysis> analyses() { var map=new HashMap<UUID,Analysis>(); db.query("SELECT * FROM analyses",r->{map.put(UUID.fromString(r.getString("execution_id")),decode(r.getString("payload"),Analysis.class));}); return map; }
    public void saveSpecification(Project project,SavedSpecification spec,boolean create) {
        tx.executeWithoutResult(s->{
            if(create) db.update("INSERT INTO projects(id,name,created_at,deleting) VALUES(?,?,?,FALSE)",project.id().toString(),project.name(),project.createdAt().toString());
            db.update("INSERT INTO specifications(id,project_id,version,payload,source) VALUES(?,?,?,?,?)",spec.specification().id().toString(),project.id().toString(),spec.version(),json(spec.specification()),json(spec.source()));
        });
    }
    public void savePlan(Plan plan,boolean create) {
        tx.executeWithoutResult(s->{
            if(create) db.update("INSERT INTO plans(id,specification_id,payload) VALUES(?,?,?)",plan.id().toString(),plan.specificationId().toString(),json(plan));
            else db.update("UPDATE plans SET payload=? WHERE id=?",json(plan),plan.id().toString());
            db.update("INSERT INTO plan_revisions(id,plan_id,payload) VALUES(?,?,?)",UUID.randomUUID().toString(),plan.id().toString(),json(plan));
        });
    }
    public void saveExecution(Execution e) { db.update("INSERT INTO executions(id,plan_id,payload) VALUES(?,?,?)",e.id().toString(),e.planId().toString(),json(e)); }
    public void saveAnalysis(UUID id,Analysis a) { tx.executeWithoutResult(s->{db.update("DELETE FROM analyses WHERE execution_id=?",id.toString());db.update("INSERT INTO analyses(execution_id,payload) VALUES(?,?)",id.toString(),json(a));}); }
    public void rename(UUID id,String name) { db.update("UPDATE projects SET name=? WHERE id=?",name,id.toString()); }
    public void markDeleting(UUID id) { db.update("UPDATE projects SET deleting=TRUE WHERE id=?",id.toString()); }
    public void delete(UUID id) { db.update("DELETE FROM projects WHERE id=?",id.toString()); }
    @jakarta.annotation.PreDestroy
    public void close() { ds.close(); }
}
