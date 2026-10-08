package com.revesoft.tms.console;

import com.revesoft.tms.common.BusinessException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The live database schema (tables, columns, foreign keys), read from {@code information_schema}.
 * Table and column names in console SQL only ever come from here, never from the request, so the
 * generic table browser cannot be used for SQL injection.
 */
@Component
public class DbSchema {

    /** Not shown in the console. */
    private static final Set<String> HIDDEN_TABLES = Set.of("flyway_schema_history");

    /** Columns used (in this order, at most two) to describe a row in a link. */
    private static final List<String> LABEL_COLUMNS =
            List.of("code", "receipt_no", "name", "unit_no", "title", "username", "platform");

    private static final Map<String, String> GROUPS = Map.ofEntries(
            Map.entry("organizations", "Organisations"), Map.entry("users", "Organisations"),
            Map.entry("user_properties", "Organisations"), Map.entry("code_counters", "Organisations"),
            Map.entry("properties", "Properties"), Map.entry("units", "Properties"),
            Map.entry("tenants", "Tenants"), Map.entry("tenant_documents", "Tenants"),
            Map.entry("agreements", "Tenants"),
            Map.entry("rent_invoices", "Money"), Map.entry("payments", "Money"), Map.entry("expenses", "Money"),
            Map.entry("maintenance_requests", "Maintenance"), Map.entry("maintenance_history", "Maintenance"),
            Map.entry("devices", "System"), Map.entry("refresh_tokens", "System"), Map.entry("push_tokens", "System"),
            Map.entry("notifications", "System"),
            Map.entry("audit_logs", "History"), Map.entry("license_events", "History"),
            Map.entry("change_log", "History"));

    /** {@code type}: id, text, number, money, date, datetime, bool or json. */
    public record Column(String name, String type, boolean nullable, String ref, boolean secret) {
    }

    /** A foreign key pointing at a table, seen from the referencing side. */
    public record Reference(String table, String column) {
    }

    public record Table(String name, String group, List<Column> columns, List<String> primaryKey,
                        List<String> labelColumns) {

        public Column column(String name) {
            return columns.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
        }

        public boolean has(String column) {
            return column(column) != null;
        }

        /** Rows can be opened one by one (single-column {@code id} key). */
        public boolean hasId() {
            return primaryKey.equals(List.of("id"));
        }
    }

    private final JdbcTemplate jdbc;
    private volatile Map<String, Table> tables;
    private volatile Map<String, List<Reference>> referencedBy;

    public DbSchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Table> tables() {
        return List.copyOf(load().values());
    }

    public Table table(String name) {
        Table t = load().get(name);
        if (t == null) {
            throw BusinessException.notFound("Table");
        }
        return t;
    }

    /** Foreign keys in other tables that point at {@code table}. */
    public List<Reference> referencesTo(String table) {
        load();
        return referencedBy.getOrDefault(table, List.of());
    }

    private Map<String, Table> load() {
        Map<String, Table> loaded = tables;
        if (loaded == null) {
            synchronized (this) {
                if (tables == null) {
                    read();
                }
                loaded = tables;
            }
        }
        return loaded;
    }

    private void read() {
        Map<String, String> refs = new LinkedHashMap<>();
        Map<String, List<Reference>> incoming = new LinkedHashMap<>();
        jdbc.query("""
                SELECT table_name AS t, column_name AS c, referenced_table_name AS rt
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE() AND referenced_table_name IS NOT NULL
                ORDER BY table_name, column_name""", rs -> {
            String t = rs.getString("t");
            String c = rs.getString("c");
            String rt = rs.getString("rt");
            refs.put(t + "." + c, rt);
            incoming.computeIfAbsent(rt, k -> new ArrayList<>()).add(new Reference(t, c));
        });
        // Soft references the schema does not declare.
        refs.putIfAbsent("change_log.org_id", "organizations");
        refs.putIfAbsent("change_log.actor_id", "users");
        refs.putIfAbsent("audit_logs.user_id", "users");
        refs.putIfAbsent("push_tokens.user_id", "users");

        Map<String, List<String>> keys = new LinkedHashMap<>();
        jdbc.query("""
                SELECT table_name AS t, column_name AS c
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE() AND constraint_name = 'PRIMARY'
                ORDER BY table_name, ordinal_position""",
                rs -> {
                    keys.computeIfAbsent(rs.getString("t"), k -> new ArrayList<>()).add(rs.getString("c"));
                });

        Map<String, List<Column>> columns = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.table_name AS t, c.column_name AS c, c.data_type AS dt, c.column_type AS ct,
                       c.is_nullable AS n, c.extra AS x
                FROM information_schema.columns c
                JOIN information_schema.tables tb
                  ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
                WHERE c.table_schema = DATABASE() AND tb.table_type = 'BASE TABLE'
                ORDER BY c.table_name, c.ordinal_position""", rs -> {
            String t = rs.getString("t");
            String c = rs.getString("c");
            String extra = rs.getString("x");
            if (HIDDEN_TABLES.contains(t) || (extra != null && extra.toUpperCase().contains("GENERATED"))) {
                return;
            }
            String ref = refs.get(t + "." + c);
            columns.computeIfAbsent(t, k -> new ArrayList<>()).add(new Column(c,
                    type(c, rs.getString("dt"), rs.getString("ct"), ref), "YES".equals(rs.getString("n")), ref,
                    ChangeLogListener.SECRET_COLUMNS.contains(c)));
        });

        Map<String, Table> result = new LinkedHashMap<>();
        columns.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<Column>> e) -> groupOrder(e.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> {
                    List<String> names = e.getValue().stream().map(Column::name).toList();
                    List<String> label = LABEL_COLUMNS.stream().filter(names::contains).limit(2).toList();
                    result.put(e.getKey(), new Table(e.getKey(), GROUPS.getOrDefault(e.getKey(), "Other"),
                            List.copyOf(e.getValue()), keys.getOrDefault(e.getKey(), List.of()), label));
                });
        incoming.keySet().removeIf(HIDDEN_TABLES::contains);
        referencedBy = incoming;
        tables = result;
    }

    private static int groupOrder(String table) {
        List<String> order = List.of("Organisations", "Properties", "Tenants", "Money", "Maintenance", "System",
                "History", "Other");
        return order.indexOf(GROUPS.getOrDefault(table, "Other"));
    }

    private static String type(String column, String dataType, String columnType, String ref) {
        String dt = dataType.toLowerCase();
        if (ref != null || (dt.equals("char") && columnType.equals("char(36)")
                && (column.equals("id") || column.endsWith("_id")))) {
            return "id";
        }
        return switch (dt) {
            case "decimal" -> "money";
            case "int", "bigint", "smallint", "mediumint", "double", "float" -> "number";
            case "tinyint" -> columnType.startsWith("tinyint(1)") ? "bool" : "number";
            case "bit", "boolean" -> "bool";
            case "date" -> "date";
            case "datetime", "timestamp" -> "datetime";
            case "json" -> "json";
            default -> "text";
        };
    }
}
