package com.revesoft.tms.console;

import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.console.ConsoleService.ActivityPage;
import com.revesoft.tms.console.ConsoleService.ActivityQuery;
import com.revesoft.tms.console.ConsoleService.RecordView;
import com.revesoft.tms.console.ConsoleService.RowPage;
import com.revesoft.tms.console.ConsoleService.RowQuery;
import com.revesoft.tms.console.ConsoleService.Summary;
import com.revesoft.tms.console.ConsoleService.TableInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendor data console: read-only access to every table and the activity across all
 * organisations. Licence and device actions stay on {@code /api/vendor}.
 */
@RestController
@RequestMapping("/api/console")
@PreAuthorize("hasRole('VENDOR')")
public class ConsoleController {

    private final ConsoleService service;

    public ConsoleController(ConsoleService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Summary summary(@RequestParam(required = false) UUID org, @RequestParam(defaultValue = "30") int days) {
        return service.summary(org, days);
    }

    @GetMapping("/tables")
    public List<TableInfo> tables() {
        return service.tables();
    }

    /**
     * Rows of one table. {@code filter} is repeatable, {@code column:value} for an exact match
     * ({@code column:} matches NULL). {@code from}/{@code to} are dates or instants on created_at.
     */
    @GetMapping("/tables/{table}/rows")
    public RowPage rows(@PathVariable String table, @RequestParam(required = false) UUID org,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) List<String> filter,
                        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                        @RequestParam(required = false) String sort, @RequestParam(defaultValue = "false") boolean desc,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.rows(table, query(org, q, filter, from, to, sort, desc, page, size));
    }

    @GetMapping("/tables/{table}/export")
    public void export(@PathVariable String table, @RequestParam(required = false) UUID org,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) List<String> filter,
                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                       @RequestParam(required = false) String sort, @RequestParam(defaultValue = "false") boolean desc,
                       HttpServletResponse response) throws IOException {
        RowQuery query = query(org, q, filter, from, to, sort, desc, 0, 0);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + table + "-" + LocalDate.now(ZoneOffset.UTC) + ".csv\"");
        Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        service.exportCsv(table, query, out);
        out.flush();
    }

    @GetMapping("/tables/{table}/rows/{id}")
    public RecordView record(@PathVariable String table, @PathVariable String id) {
        return service.record(table, id);
    }

    /** Row changes, audit entries and licence events, newest first. Page with {@code before=nextBefore}. */
    @GetMapping("/activity")
    public ActivityPage activity(@RequestParam(required = false) UUID org,
                                 @RequestParam(required = false) Set<String> source,
                                 @RequestParam(required = false) String table,
                                 @RequestParam(required = false) String rowId,
                                 @RequestParam(required = false) String actor,
                                 @RequestParam(required = false) String op,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false) Instant before,
                                 @RequestParam(defaultValue = "50") int limit) {
        return service.activity(new ActivityQuery(org, source, blank(table), blank(rowId), actor, blank(op),
                time(from, false), time(to, true), before, limit));
    }

    private static RowQuery query(UUID org, String q, List<String> filter, String from, String to, String sort,
                                  boolean desc, int page, int size) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (filter != null) {
            for (String f : filter) {
                int colon = f.indexOf(':');
                if (colon <= 0) {
                    throw new BusinessException("Filters look like column:value");
                }
                filters.put(f.substring(0, colon), f.substring(colon + 1));
            }
        }
        return new RowQuery(org, q, filters, time(from, false), time(to, true), blank(sort), desc, page, size);
    }

    /** A date (whole day, UTC; {@code to} is inclusive) or an ISO instant. */
    private static Instant time(String value, boolean end) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.length() == 10) {
                LocalDate d = LocalDate.parse(value);
                return (end ? d.plusDays(1) : d).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new BusinessException("Invalid date: " + value);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
