package com.revesoft.tms.console;

import com.revesoft.tms.common.BaseEntity;
import com.revesoft.tms.org.Organization;
import com.revesoft.tms.security.AuthUser;
import com.revesoft.tms.security.CurrentUser;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.HibernateException;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one {@code change_log} row for every entity insert, update and delete, in the same
 * transaction as the change, so the vendor console can show the history of every table.
 *
 * <p>Bulk JPQL/SQL statements ({@code @Modifying} queries) bypass Hibernate events and are not
 * recorded; neither are element collections changed on their own (a manager's property list).
 */
@Component
public class ChangeLogListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    /** Tables with their own history (or too noisy to be useful). */
    private static final Set<String> SKIPPED_TABLES = Set.of("refresh_tokens", "audit_logs", "license_events");

    /** Never shown; changes to them are recorded as "***". */
    static final Set<String> SECRET_COLUMNS = Set.of("password_hash", "token_hash", "token");

    /** Bookkeeping columns that change on every update. */
    private static final Set<String> IGNORED_COLUMNS = Set.of("version", "updated_at");

    private static final Logger log = LoggerFactory.getLogger(ChangeLogListener.class);
    private static final String MASK = "***";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INSERT_SQL = """
            INSERT INTO change_log (org_id, table_name, row_id, op, changes, actor_id, actor_name, actor_role,
                                    session_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final EntityManagerFactory emf;

    public ChangeLogListener(EntityManagerFactory emf) {
        this.emf = emf;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = emf.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().requireService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        String[] names = event.getPersister().getPropertyNames();
        Object[] state = event.getState();
        for (int i = 0; i < names.length; i++) {
            String column = column(event.getPersister(), i, names[i]);
            Object value = value(column, state[i]);
            if (value != null && !IGNORED_COLUMNS.contains(column) && !"created_at".equals(column)) {
                changes.put(column, entry(null, value, false));
            }
        }
        write(event.getSession(), event.getPersister(), event.getEntity(), event.getId(), "INSERT", changes);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        String[] names = event.getPersister().getPropertyNames();
        Object[] state = event.getState();
        Object[] old = event.getOldState();
        for (int i : candidates(event.getDirtyProperties(), names.length)) {
            String column = column(event.getPersister(), i, names[i]);
            if (IGNORED_COLUMNS.contains(column)) {
                continue;
            }
            Object before = old == null ? null : value(column, old[i]);
            Object after = value(column, state[i]);
            if (old == null || !same(before, after)) {
                changes.put(column, entry(before, after, true));
            }
        }
        if (!changes.isEmpty()) {
            write(event.getSession(), event.getPersister(), event.getEntity(), event.getId(), "UPDATE", changes);
        }
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        String[] names = event.getPersister().getPropertyNames();
        Object[] state = event.getDeletedState();
        for (int i = 0; state != null && i < names.length; i++) {
            String column = column(event.getPersister(), i, names[i]);
            Object value = value(column, state[i]);
            if (value != null && !IGNORED_COLUMNS.contains(column)) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("old", value);
                changes.put(column, e);
            }
        }
        write(event.getSession(), event.getPersister(), event.getEntity(), event.getId(), "DELETE", changes);
    }

    // ---------------------------------------------------------------- helpers

    private void write(SharedSessionContractImplementor session, EntityPersister persister, Object entity, Object id,
                       String op, Map<String, Map<String, Object>> changes) {
        String table = persister.getTableName();
        if (SKIPPED_TABLES.contains(table)) {
            return;
        }
        UUID orgId = orgOf(entity);
        if (orgId == null) {
            return;
        }
        AuthUser actor = CurrentUser.orNull();
        Connection connection = session.getJdbcCoordinator().getLogicalConnection().getPhysicalConnection();
        String json = JSON.writeValueAsString(changes);
        log.debug("{} {} {} {}", op, table, id, json);
        try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            ps.setString(1, orgId.toString());
            ps.setString(2, table);
            ps.setString(3, String.valueOf(id));
            ps.setString(4, op);
            ps.setString(5, json);
            // Jobs run as a system identity without a user id.
            ps.setString(6, actor == null || actor.userId() == null ? null : actor.userId().toString());
            ps.setString(7, actor == null || actor.name() == null ? "System" : actor.name());
            ps.setString(8, actor == null || actor.role() == null ? null : actor.role().name());
            ps.setString(9, session.getSessionIdentifier().toString());
            ps.setObject(10, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new HibernateException("Could not write the change log for " + table + " " + id, e);
        }
    }

    private static UUID orgOf(Object entity) {
        if (entity instanceof BaseEntity b) {
            return b.getOrgId();
        }
        if (entity instanceof Organization o) {
            return o.getId();
        }
        return null;
    }

    private static int[] candidates(int[] dirty, int count) {
        if (dirty != null) {
            return dirty;
        }
        int[] all = new int[count];
        for (int i = 0; i < count; i++) {
            all[i] = i;
        }
        return all;
    }

    private static Map<String, Object> entry(Object before, Object after, boolean withOld) {
        Map<String, Object> e = new LinkedHashMap<>();
        if (withOld) {
            e.put("old", before);
        }
        e.put("new", after);
        return e;
    }

    /** JSON-friendly form of a property value. */
    static Object value(String column, Object v) {
        if (v == null) {
            return null;
        }
        if (SECRET_COLUMNS.contains(column)) {
            return MASK;
        }
        if (v instanceof String || v instanceof Boolean || v instanceof Number) {
            return v;
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof PersistentCollection<?> pc && !pc.wasInitialized()) {
            return null;
        }
        if (v instanceof Collection<?> c) {
            List<String> items = new ArrayList<>();
            c.forEach(item -> items.add(String.valueOf(item)));
            items.sort(null);
            return items;
        }
        return v.toString();
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    /** The property's column; a collection has none, so it is named after the property. */
    private static String column(EntityPersister persister, int index, String property) {
        if (persister instanceof AbstractEntityPersister p) {
            String[] columns = p.getPropertyColumnNames(index);
            if (columns.length == 1) {
                return columns[0];
            }
        }
        return property.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
