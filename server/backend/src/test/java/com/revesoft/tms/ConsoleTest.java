package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The vendor data console: change history for every table, table browser, record view, activity. */
class ConsoleTest extends ApiTestBase {

    @Test
    void onlyTheVendorCanUseTheConsole() throws Exception {
        Org org = newOrg();
        get(org.token(), "/api/console/tables").andReturnBodyExpecting(403);
        get(org.token(), "/api/console/activity").andReturnBodyExpecting(403);
        get(null, "/api/console/tables").andReturnBodyExpecting(401);
    }

    @Test
    void everyInsertUpdateAndDeleteIsRecorded() throws Exception {
        Org org = newOrg();
        String t = org.token();
        String vendor = vendorToken();

        String propertyId = createProperty(t, "Green View");
        put(t, "/api/properties/" + propertyId, """
                {"name":"Green View Tower","type":"APARTMENT","address":"Road 1","city":"Dhaka","floors":5}""").ok();
        String unitId = createUnit(t, propertyId, "A1", 15000);
        delete(t, "/api/units/" + unitId).andReturnBodyExpecting(204);

        String changes = get(vendor, "/api/console/activity?source=change&org=" + org.id() + "&limit=200").ok();
        List<String> propertyOps = read(changes,
                "$.items[?(@.table == 'properties' && @.rowId == '" + propertyId + "')].action");
        assertThat(propertyOps).containsExactly("UPDATE", "INSERT");
        List<String> unitOps = read(changes, "$.items[?(@.rowId == '" + unitId + "')].action");
        assertThat(unitOps).containsExactly("DELETE", "INSERT");

        // The update records only what changed, old and new, and who did it.
        List<Map<String, Object>> updates = read(changes,
                "$.items[?(@.table == 'properties' && @.action == 'UPDATE')]");
        Map<String, Object> update = updates.getFirst();
        assertThat(update.get("actor")).isEqualTo("Owner");
        assertThat(update.get("actorRole")).isEqualTo("ADMIN");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> diff = (Map<String, Map<String, Object>>) update.get("changes");
        assertThat(diff).containsOnlyKeys("name", "floors");
        assertThat(diff.get("name")).containsEntry("old", "Green View").containsEntry("new", "Green View Tower");

        // Password hashes never appear in the history or the table browser.
        String userChanges = get(vendor, "/api/console/activity?source=change&table=users&org=" + org.id()).ok();
        assertThat((List<String>) read(userChanges, "$.items[*].changes.password_hash.new")).containsOnly("***");
        String users = get(vendor, "/api/console/tables/users/rows?org=" + org.id()).ok();
        assertThat((List<String>) read(users, "$.rows[*].password_hash")).containsOnly("***");
    }

    @Test
    void tableBrowserFiltersByOrganisationAndResolvesLinks() throws Exception {
        Org a = newOrg();
        Org b = newOrg();
        String vendor = vendorToken();
        String propertyId = createProperty(a.token(), "North Court");
        createUnit(a.token(), propertyId, "N1", 12000);
        createProperty(b.token(), "South Court");

        List<String> tables = read(get(vendor, "/api/console/tables").ok(), "$[*].name");
        assertThat(tables).contains("organizations", "properties", "units", "tenants", "change_log")
                .doesNotContain("flyway_schema_history");

        String page = get(vendor, "/api/console/tables/properties/rows?org=" + a.id()).ok();
        assertThat((Integer) read(page, "$.total")).isEqualTo(1);
        assertThat((String) read(page, "$.rows[0].name")).isEqualTo("North Court");

        // Foreign keys come with labels for the linked rows.
        String units = get(vendor, "/api/console/tables/units/rows?filter=property_id:" + propertyId).ok();
        assertThat((Integer) read(units, "$.total")).isEqualTo(1);
        assertThat((String) read(units, "$.lookups.properties['" + propertyId + "']")).contains("North Court");
        assertThat((String) read(units, "$.lookups.organizations['" + a.id() + "']")).startsWith("Estate ");

        // Search, and unknown tables or columns are refused.
        assertThat((Integer) read(get(vendor, "/api/console/tables/properties/rows?q=South&org=" + b.id()).ok(),
                "$.total")).isEqualTo(1);
        get(vendor, "/api/console/tables/nope/rows").andReturnBodyExpecting(404);
        get(vendor, "/api/console/tables/units/rows?filter=nope:1").andReturnBodyExpecting(400);
        get(vendor, "/api/console/tables/units/rows?sort=id;DROP TABLE units").ok();

        // One record: its links, the rows pointing at it and its history.
        String record = get(vendor, "/api/console/tables/properties/rows/" + propertyId).ok();
        assertThat((String) read(record, "$.row.name")).isEqualTo("North Court");
        assertThat((List<String>) read(record, "$.referencedBy[*].table")).contains("units");
        assertThat((List<String>) read(record, "$.history[*].action")).contains("INSERT");

        // Organisation summary counts only that organisation's rows.
        String summary = get(vendor, "/api/console/summary?org=" + a.id()).ok();
        assertThat((List<Integer>) read(summary, "$.tables[?(@.name == 'units')].rows")).containsExactly(1);
        assertThat((Integer) read(summary, "$.changesToday")).isPositive();

        String csv = download(vendor, "/api/console/tables/properties/export?org=" + a.id(), 200)
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("North Court").doesNotContain("South Court");
    }
}
