package com.revesoft.tms.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic tabular report. The apps render the JSON form directly; {@link XlsxRenderer} and
 * {@link PdfRenderer} render the same model.
 *
 * <p>Row and total values are keyed by {@link Column#key()}: text columns hold strings, money /
 * number / percent columns hold numbers (percent as 0-100), date columns hold ISO dates. Null values
 * are omitted from the JSON. {@code summary} holds extra labelled figures such as totals by payment
 * method.
 */
public record ReportTable(String title, String subtitle, List<Column> columns, List<Map<String, Object>> rows,
                          Map<String, Object> totals, List<SummaryItem> summary) {

    public static final String TEXT = "text";
    public static final String MONEY = "money";
    public static final String DATE = "date";
    public static final String NUMBER = "number";
    public static final String PERCENT = "percent";

    /** {@code type}: text | money | date | number | percent. */
    public record Column(String key, String label, String type) {
    }

    public record SummaryItem(String label, Object value, String type) {
    }

    /** Collects columns, rows and totals in order. */
    public static final class Builder {

        private final String title;
        private final String subtitle;
        private final List<Column> columns = new ArrayList<>();
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private Map<String, Object> totals;
        private final List<SummaryItem> summary = new ArrayList<>();

        public Builder(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        public Builder column(String key, String label, String type) {
            columns.add(new Column(key, label, type));
            return this;
        }

        /** Values in column order. */
        public Builder row(Object... values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size() && i < values.length; i++) {
                row.put(columns.get(i).key(), values[i]);
            }
            rows.add(row);
            return this;
        }

        /** Alternating key/value pairs, e.g. {@code totals("tenant", "Total", "amount", 1200)}. */
        public Builder totals(Object... keyValues) {
            totals = new LinkedHashMap<>();
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                totals.put((String) keyValues[i], keyValues[i + 1]);
            }
            return this;
        }

        public Builder summary(String label, Object value, String type) {
            summary.add(new SummaryItem(label, value, type));
            return this;
        }

        public ReportTable build() {
            return new ReportTable(title, subtitle, List.copyOf(columns), rows, totals,
                    summary.isEmpty() ? null : List.copyOf(summary));
        }
    }
}
