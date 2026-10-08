package com.revesoft.tms.console;

import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.console.DbSchema.Column;
import com.revesoft.tms.console.DbSchema.Reference;
import com.revesoft.tms.console.DbSchema.Table;
import java.io.IOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only access to every table across all organisations, for the vendor data console.
 *
 * <p>This uses plain JDBC, which Hibernate's organisation filter does not apply to: the caller is
 * checked for the VENDOR role in {@link ConsoleController}. Identifiers in SQL come only from
 * {@link DbSchema}; all values are bound as parameters.
 */
@Service
public class ConsoleService {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_EXPORT_ROWS = 50_000;
    private static final String MASK = "***";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final DbSchema schema;

    public ConsoleService(JdbcTemplate jdbc, DbSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    // ---------------------------------------------------------------- DTOs

    public record TableInfo(String name, String group, List<Column> columns, List<String> primaryKey,
                            boolean openable, boolean orgScoped, long rows) {
    }

    /** {@code filters}: exact matches, column → value. {@code from}/{@code to} limit created_at. */
    public record RowQuery(UUID org, String q, Map<String, String> filters, Instant from, Instant to, String sort,
                           boolean desc, int page, int size) {
    }

    /** {@code lookups}: referenced table → (id → label), for showing links instead of raw ids. */
    public record RowPage(String table, List<Column> columns, List<Map<String, Object>> rows, long total, int page,
                          int size, Map<String, Map<String, String>> lookups) {
    }

    public record ReferenceCount(String table, String column, long count) {
    }

    public record RecordView(String table, List<Column> columns, Map<String, Object> row, String label,
                             Map<String, Map<String, String>> lookups, List<ReferenceCount> referencedBy,
                             List<Activity> history) {
    }

    /**
     * One entry of the activity feed. {@code source}: change (row change), audit (business action
     * from the audit log) or license (licensing event).
     */
    public record Activity(String source, String key, Instant at, UUID orgId, String orgName, String actor,
                           String actorRole, String action, String table, String rowId, String details,
                           JsonNode changes) {
    }

    public record ActivityQuery(UUID org, Set<String> sources, String table, String rowId, String actor, String op,
                                Instant from, Instant to, Instant before, int limit) {
    }

    public record ActivityPage(List<Activity> items, Instant nextBefore) {
    }

    public record DayCount(LocalDate day, long inserts, long updates, long deletes) {
    }

    public record Summary(List<TableCount> tables, List<DayCount> activityByDay, long changesToday) {
    }

    public record TableCount(String name, String group, long rows) {
    }

    // ---------------------------------------------------------------- tables

    public List<TableInfo> tables() {
        return schema.tables().stream()
                .map(t -> new TableInfo(t.name(), t.group(), t.columns(), t.primaryKey(), t.hasId(),
                        orgCondition(t, "t") != null, count(t, null)))
                .toList();
    }

    public RowPage rows(String tableName, RowQuery query) {
        Table t = schema.table(tableName);
        List<Object> args = new ArrayList<>();
        String where = where(t, query, args);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + quote(t.name()) + " t" + where, Long.class,
                args.toArray());
        int size = Math.clamp(query.size(), 1, MAX_PAGE_SIZE);
        int page = Math.max(query.page(), 0);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Map<String, Object>> rows = jdbc.query(select(t) + where + orderBy(t, query) + " LIMIT ? OFFSET ?",
                (rs, n) -> row(t, rs), pageArgs.toArray());
        return new RowPage(t.name(), t.columns(), rows, total, page, size, lookups(t, rows));
    }

    /** Writes the matching rows (at most {@link #MAX_EXPORT_ROWS}) as CSV. */
    public void exportCsv(String tableName, RowQuery query, Writer out) throws IOException {
        Table t = schema.table(tableName);
        List<Object> args = new ArrayList<>();
        String where = where(t, query, args);
        args.add(MAX_EXPORT_ROWS);
        out.write('﻿'); // Excel needs the BOM to read UTF-8 (Bangla names).
        out.write(t.columns().stream().map(Column::name).map(ConsoleService::csv).collect(Collectors.joining(",")));
        out.write("\r\n");
        jdbc.query(select(t) + where + orderBy(t, query) + " LIMIT ?", rs -> {
            Map<String, Object> row = row(t, rs);
            try {
                out.write(t.columns().stream().map(c -> csv(row.get(c.name()))).collect(Collectors.joining(",")));
                out.write("\r\n");
            } catch (IOException e) {
                throw new SQLException("Export interrupted", e);
            }
        }, args.toArray());
    }

    public RecordView record(String tableName, String id) {
        Table t = schema.table(tableName);
        if (!t.hasId()) {
            throw BusinessException.notFound("Record");
        }
        List<Map<String, Object>> found = jdbc.query(select(t) + " WHERE t.id = ?", (rs, n) -> row(t, rs), id);
        if (found.isEmpty()) {
            throw BusinessException.notFound("Record");
        }
        Map<String, Object> row = found.getFirst();

        List<ReferenceCount> referencedBy = new ArrayList<>();
        for (Reference ref : schema.referencesTo(t.name())) {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + quote(ref.table()) + " WHERE "
                    + quote(ref.column()) + " = ?", Long.class, id);
            if (n != null && n > 0) {
                referencedBy.add(new ReferenceCount(ref.table(), ref.column(), n));
            }
        }
        referencedBy.sort(Comparator.comparing(ReferenceCount::table));

        Map<String, Map<String, String>> lookups = lookups(t, found);
        String label = labels(t, Set.of(id)).get(id);
        List<Activity> history = activity(new ActivityQuery(null, Set.of("change", "audit"), t.name(), id, null,
                null, null, null, null, MAX_PAGE_SIZE)).items();
        return new RecordView(t.name(), t.columns(), row, label, lookups, referencedBy, history);
    }

    // ---------------------------------------------------------------- activity

    public ActivityPage activity(ActivityQuery q) {
        int limit = Math.clamp(q.limit(), 1, MAX_PAGE_SIZE);
        Set<String> sources = q.sources() == null || q.sources().isEmpty()
                ? Set.of("change", "audit", "license") : q.sources();
        List<Activity> items = new ArrayList<>();
        if (sources.contains("change")) {
            items.addAll(changes(q, limit));
        }
        // Audit and licence entries are not row changes: they cannot be filtered by operation.
        if (sources.contains("audit") && q.op() == null) {
            items.addAll(audits(q, limit));
        }
        if (sources.contains("license") && q.op() == null && q.rowId() == null
                && (q.table() == null || q.table().equals("organizations"))) {
            items.addAll(licenseEvents(q, limit));
        }
        items.sort(Comparator.comparing(Activity::at).reversed().thenComparing(Activity::key));
        List<Activity> pageItems = items.size() > limit ? items.subList(0, limit) : items;
        Map<UUID, String> orgNames = orgNames(pageItems.stream().map(Activity::orgId).collect(Collectors.toSet()));
        List<Activity> named = pageItems.stream().map(a -> new Activity(a.source(), a.key(), a.at(), a.orgId(),
                orgNames.get(a.orgId()), a.actor(), a.actorRole(), a.action(), a.table(), a.rowId(), a.details(),
                a.changes())).toList();
        Instant next = items.size() > limit ? named.getLast().at() : null;
        return new ActivityPage(named, next);
    }

    private List<Activity> changes(ActivityQuery q, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, created_at, org_id, actor_name, actor_role, op, table_name, row_id, changes
                FROM change_log WHERE 1 = 1""");
        common(q, sql, args, "org_id", "actor_name", "created_at");
        if (q.table() != null) {
            sql.append(" AND table_name = ?");
            args.add(q.table());
        }
        if (q.rowId() != null) {
            sql.append(" AND row_id = ?");
            args.add(q.rowId());
        }
        if (q.op() != null) {
            sql.append(" AND op = ?");
            args.add(q.op());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, n) -> new Activity("change", "c" + rs.getLong("id"),
                instant(rs, "created_at"), uuid(rs.getString("org_id")), null, rs.getString("actor_name"),
                rs.getString("actor_role"), rs.getString("op"), rs.getString("table_name"), rs.getString("row_id"),
                null, JSON.readTree(rs.getString("changes"))), args.toArray());
    }

    private List<Activity> audits(ActivityQuery q, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, created_at, org_id, user_name, action, entity_type, entity_id, details
                FROM audit_logs WHERE 1 = 1""");
        common(q, sql, args, "org_id", "user_name", "created_at");
        if (q.rowId() != null) {
            sql.append(" AND entity_id = ?");
            args.add(q.rowId());
        } else if (q.table() != null) {
            // Audit entries name the entity, not the table; they only belong to a single record's history.
            return List.of();
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, n) -> new Activity("audit", "a" + rs.getString("id"),
                instant(rs, "created_at"), uuid(rs.getString("org_id")), null, rs.getString("user_name"), null,
                rs.getString("action"), rs.getString("entity_type"), rs.getString("entity_id"),
                rs.getString("details"), null), args.toArray());
    }

    private List<Activity> licenseEvents(ActivityQuery q, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, created_at, org_id, actor_name, action, details FROM license_events WHERE 1 = 1""");
        common(q, sql, args, "org_id", "actor_name", "created_at");
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, n) -> new Activity("license", "l" + rs.getString("id"),
                instant(rs, "created_at"), uuid(rs.getString("org_id")), null, rs.getString("actor_name"), "VENDOR",
                rs.getString("action"), "organizations", rs.getString("org_id"), rs.getString("details"), null),
                args.toArray());
    }

    private static void common(ActivityQuery q, StringBuilder sql, List<Object> args, String orgCol, String actorCol,
                               String timeCol) {
        if (q.org() != null) {
            sql.append(" AND ").append(orgCol).append(" = ?");
            args.add(q.org().toString());
        }
        if (q.actor() != null && !q.actor().isBlank()) {
            sql.append(" AND ").append(actorCol).append(" LIKE ?");
            args.add("%" + q.actor().trim() + "%");
        }
        if (q.from() != null) {
            sql.append(" AND ").append(timeCol).append(" >= ?");
            args.add(utc(q.from()));
        }
        if (q.to() != null) {
            sql.append(" AND ").append(timeCol).append(" < ?");
            args.add(utc(q.to()));
        }
        if (q.before() != null) {
            sql.append(" AND ").append(timeCol).append(" < ?");
            args.add(utc(q.before()));
        }
    }

    // ---------------------------------------------------------------- summary

    /** Row counts per table and daily change counts (last {@code days} days), optionally for one organisation. */
    public Summary summary(UUID org, int days) {
        List<TableCount> counts = schema.tables().stream()
                .filter(t -> org == null || orgCondition(t, "t") != null)
                .map(t -> new TableCount(t.name(), t.group(), count(t, org)))
                .toList();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate first = today.minusDays(Math.clamp(days, 1, 366) - 1L);
        List<Object> args = new ArrayList<>();
        args.add(first.atStartOfDay());
        String orgWhere = "";
        if (org != null) {
            orgWhere = " AND org_id = ?";
            args.add(org.toString());
        }
        Map<LocalDate, DayCount> byDay = new HashMap<>();
        jdbc.query("""
                SELECT DATE(created_at) AS d, SUM(op = 'INSERT') AS i, SUM(op = 'UPDATE') AS u, SUM(op = 'DELETE') AS x
                FROM change_log WHERE created_at >= ?""" + orgWhere + " GROUP BY DATE(created_at)", rs -> {
            LocalDate d = rs.getObject("d", LocalDate.class);
            byDay.put(d, new DayCount(d, rs.getLong("i"), rs.getLong("u"), rs.getLong("x")));
        }, args.toArray());
        List<DayCount> series = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            series.add(byDay.getOrDefault(d, new DayCount(d, 0, 0, 0)));
        }
        DayCount t = series.getLast();
        return new Summary(counts, series, t.inserts() + t.updates() + t.deletes());
    }

    // ---------------------------------------------------------------- SQL building

    private long count(Table t, UUID org) {
        List<Object> args = new ArrayList<>();
        String where = "";
        if (org != null) {
            String cond = orgCondition(t, "t");
            if (cond == null) {
                return 0;
            }
            where = " WHERE " + cond;
            args.add(org.toString());
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + quote(t.name()) + " t" + where, Long.class,
                args.toArray());
        return n == null ? 0 : n;
    }

    /** SQL that limits {@code alias} to one organisation (one {@code ?}), or null if the table has no owner. */
    private String orgCondition(Table t, String alias) {
        if (t.name().equals("organizations")) {
            return alias + ".id = ?";
        }
        if (t.has("org_id")) {
            return alias + ".org_id = ?";
        }
        if (t.has("user_id") && "users".equals(t.column("user_id").ref())) {
            return alias + ".user_id IN (SELECT u.id FROM users u WHERE u.org_id = ?)";
        }
        return null;
    }

    private String where(Table t, RowQuery q, List<Object> args) {
        List<String> parts = new ArrayList<>();
        if (q.org() != null) {
            String cond = orgCondition(t, "t");
            if (cond == null) {
                throw new BusinessException("Table " + t.name() + " does not belong to an organisation");
            }
            parts.add(cond);
            args.add(q.org().toString());
        }
        if (q.filters() != null) {
            q.filters().forEach((name, value) -> {
                Column c = t.column(name);
                if (c == null || c.secret()) {
                    throw new BusinessException("Unknown column: " + name);
                }
                if (value == null || value.isEmpty()) {
                    parts.add("t." + quote(c.name()) + " IS NULL");
                } else {
                    parts.add("t." + quote(c.name()) + " = ?");
                    args.add(c.type().equals("bool") ? Boolean.valueOf(value) : value);
                }
            });
        }
        if (q.q() != null && !q.q().isBlank()) {
            List<String> searchable = t.columns().stream()
                    .filter(c -> !c.secret() && List.of("id", "text", "number", "money", "date").contains(c.type()))
                    .map(c -> "t." + quote(c.name()))
                    .toList();
            if (!searchable.isEmpty()) {
                parts.add("CONCAT_WS(' ', " + String.join(", ", searchable) + ") LIKE ?");
                args.add("%" + q.q().trim() + "%");
            }
        }
        if (t.has("created_at")) {
            if (q.from() != null) {
                parts.add("t.created_at >= ?");
                args.add(utc(q.from()));
            }
            if (q.to() != null) {
                parts.add("t.created_at < ?");
                args.add(utc(q.to()));
            }
        }
        return parts.isEmpty() ? "" : " WHERE " + String.join(" AND ", parts);
    }

    private String orderBy(Table t, RowQuery q) {
        String sort = q.sort();
        boolean desc = q.desc();
        if (sort == null || t.column(sort) == null) {
            if (t.has("created_at")) {
                sort = "created_at";
                desc = true;
            } else {
                sort = t.primaryKey().isEmpty() ? t.columns().getFirst().name() : t.primaryKey().getFirst();
            }
        }
        String tie = t.primaryKey().stream().filter(k -> !k.equals(q.sort())).map(k -> ", t." + quote(k))
                .collect(Collectors.joining());
        return " ORDER BY t." + quote(sort) + (desc ? " DESC" : " ASC") + tie;
    }

    private static String select(Table t) {
        return "SELECT " + t.columns().stream().map(c -> "t." + quote(c.name())).collect(Collectors.joining(", "))
                + " FROM " + quote(t.name()) + " t";
    }

    private static Map<String, Object> row(Table t, ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Column c : t.columns()) {
            Object v = rs.getObject(c.name());
            if (v == null) {
                row.put(c.name(), null);
            } else if (c.secret()) {
                row.put(c.name(), MASK);
            } else {
                row.put(c.name(), switch (c.type()) {
                    case "datetime" -> instant(rs, c.name()).toString();
                    case "date" -> rs.getObject(c.name(), LocalDate.class).toString();
                    case "bool" -> v instanceof Boolean b ? b : ((Number) v).intValue() != 0;
                    case "json" -> JSON.readTree(v.toString());
                    default -> v instanceof Number ? v : v.toString();
                });
            }
        }
        return row;
    }

    /** Labels for every foreign key value on the page, grouped by referenced table. */
    private Map<String, Map<String, String>> lookups(Table t, List<Map<String, Object>> rows) {
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        for (Column c : t.columns()) {
            if (c.ref() == null) {
                continue;
            }
            for (Map<String, Object> row : rows) {
                Object v = row.get(c.name());
                if (v != null) {
                    wanted.computeIfAbsent(c.ref(), k -> new LinkedHashSet<>()).add(v.toString());
                }
            }
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        wanted.forEach((ref, ids) -> result.put(ref, labels(schema.table(ref), ids)));
        return result;
    }

    private Map<String, String> labels(Table t, Set<String> ids) {
        if (ids.isEmpty() || !t.hasId() || t.labelColumns().isEmpty()) {
            return Map.of();
        }
        String label = t.labelColumns().size() == 1 ? "t." + quote(t.labelColumns().getFirst())
                : "CONCAT_WS(' · ', " + t.labelColumns().stream().map(c -> "t." + quote(c))
                        .collect(Collectors.joining(", ")) + ")";
        Map<String, String> out = new HashMap<>();
        String in = ids.stream().map(i -> "?").collect(Collectors.joining(","));
        jdbc.query("SELECT t.id, " + label + " AS label FROM " + quote(t.name()) + " t WHERE t.id IN (" + in + ")",
                rs -> {
                    out.put(rs.getString("id"), rs.getString("label"));
                }, ids.toArray());
        return out;
    }

    private Map<UUID, String> orgNames(Set<UUID> ids) {
        Set<String> keys = ids.stream().filter(java.util.Objects::nonNull).map(UUID::toString)
                .collect(Collectors.toSet());
        return labels(schema.table("organizations"), keys).entrySet().stream()
                .collect(Collectors.toMap(e -> UUID.fromString(e.getKey()), Map.Entry::getValue));
    }

    // ---------------------------------------------------------------- small helpers

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /** DATETIME columns hold UTC. */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime v = rs.getObject(column, LocalDateTime.class);
        return v == null ? null : v.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = v.toString();
        // Keep Excel from running cell text as a formula.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
