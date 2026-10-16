package com.hengzhang;

import java.sql.PreparedStatement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import static com.hengzhang.Domain.*;

@Component
public class Store {
    final JdbcTemplate jdbc;
    public Store(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement p = c.prepareStatement(sql, new String[]{"id"});
            for (int i=0; i<args.length; i++) p.setObject(i+1, args[i]);
            return p;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public Map<String,Object> one(String sql, Object... args) {
        var rows = list(sql, args);
        if (rows.isEmpty()) throw new Problem("记录不存在");
        return rows.getFirst();
    }
    public List<Map<String,Object>> list(String sql, Object... args) {
        return jdbc.query(sql, (rs,n) -> {
            var result = new LinkedHashMap<String,Object>();
            var meta = rs.getMetaData();
            for (int i=1; i<=meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
                Object value = rs.getObject(i);
                if (value instanceof java.sql.Clob clob) value = clob.getSubString(1, Math.toIntExact(clob.length()));
                if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
                if (value instanceof java.sql.Timestamp time) value = time.toLocalDateTime().toString();
                result.put(label, value);
            }
            return result;
        }, args);
    }
    public long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    public void audit(String actor, String action, Object target, String detail) {
        jdbc.update("INSERT INTO audit_events(actor,action,target,detail) VALUES(?,?,?,?)",actor,action,target.toString(),detail);
    }
}
