package com.revesoft.tms.report;

import com.revesoft.tms.report.ReportTable.Column;
import com.revesoft.tms.report.ReportTable.SummaryItem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Renders a {@link ReportTable} as an Excel workbook (.xlsx). */
@Component
public class XlsxRenderer {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public byte[] render(ReportTable table, String orgName, String generatedAt) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles s = new Styles(wb);
            Sheet sheet = wb.createSheet(WorkbookUtil.createSafeSheetName(table.title()));
            List<Column> cols = table.columns();
            int r = 0;

            cell(sheet.createRow(r++), 0, table.title(), s.title);
            if (table.subtitle() != null) {
                cell(sheet.createRow(r++), 0, table.subtitle(), null);
            }
            cell(sheet.createRow(r++), 0, orgName + " · Generated " + generatedAt, s.muted);
            r++;

            Row header = sheet.createRow(r++);
            for (int c = 0; c < cols.size(); c++) {
                cell(header, c, cols.get(c).label(), s.header);
            }
            int headerRow = r - 1;
            for (Map<String, Object> row : table.rows()) {
                write(sheet.createRow(r++), cols, row, s, false);
            }
            if (table.totals() != null) {
                write(sheet.createRow(r++), cols, table.totals(), s, true);
            }
            if (table.summary() != null) {
                r++;
                for (SummaryItem item : table.summary()) {
                    Row row = sheet.createRow(r++);
                    cell(row, 0, item.label(), s.bold);
                    value(row.createCell(1), item.value(), item.type(), s, false);
                }
            }

            sheet.createFreezePane(0, headerRow + 1);
            for (int c = 0; c < cols.size(); c++) {
                try {
                    sheet.autoSizeColumn(c);
                    sheet.setColumnWidth(c, Math.min(Math.max(sheet.getColumnWidth(c) + 512, 10 * 256), 60 * 256));
                } catch (RuntimeException | Error e) { // no fonts on a headless server
                    sheet.setColumnWidth(c, 18 * 256);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Row row, List<Column> cols, Map<String, Object> values, Styles s, boolean total) {
        for (int c = 0; c < cols.size(); c++) {
            Column col = cols.get(c);
            Cell cell = row.createCell(c);
            value(cell, values.get(col.key()), col.type(), s, total);
        }
    }

    private static void value(Cell cell, Object v, String type, Styles s, boolean total) {
        if (v == null) {
            if (total) {
                cell.setCellStyle(s.totalText);
            }
            return;
        }
        switch (type) {
            case ReportTable.MONEY -> {
                cell.setCellValue(number(v));
                cell.setCellStyle(total ? s.totalMoney : s.money);
            }
            case ReportTable.NUMBER -> {
                cell.setCellValue(number(v));
                cell.setCellStyle(total ? s.totalNumber : s.number);
            }
            case ReportTable.PERCENT -> {
                cell.setCellValue(number(v) / 100.0);
                cell.setCellStyle(total ? s.totalPercent : s.percent);
            }
            case ReportTable.DATE -> {
                if (v instanceof LocalDate d) {
                    cell.setCellValue(d);
                    cell.setCellStyle(total ? s.totalDate : s.date);
                } else {
                    cell.setCellValue(v.toString());
                }
            }
            default -> {
                cell.setCellValue(v.toString());
                if (total) {
                    cell.setCellStyle(s.totalText);
                }
            }
        }
    }

    private static double number(Object v) {
        if (v instanceof BigDecimal b) {
            return b.doubleValue();
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle muted;
        final CellStyle bold;
        final CellStyle header;
        final CellStyle money;
        final CellStyle number;
        final CellStyle percent;
        final CellStyle date;
        final CellStyle totalText;
        final CellStyle totalMoney;
        final CellStyle totalNumber;
        final CellStyle totalPercent;
        final CellStyle totalDate;

        Styles(XSSFWorkbook wb) {
            DataFormat fmt = wb.createDataFormat();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            Font mutedFont = wb.createFont();
            mutedFont.setItalic(true);
            mutedFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

            title = wb.createCellStyle();
            title.setFont(titleFont);
            muted = wb.createCellStyle();
            muted.setFont(mutedFont);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);
            header = wb.createCellStyle();
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            money = format(wb, fmt.getFormat("#,##0.00"), null);
            number = format(wb, fmt.getFormat("#,##0.##"), null);
            percent = format(wb, fmt.getFormat("0.0%"), null);
            date = format(wb, fmt.getFormat("yyyy-mm-dd"), null);
            totalText = format(wb, (short) 0, boldFont);
            totalMoney = format(wb, fmt.getFormat("#,##0.00"), boldFont);
            totalNumber = format(wb, fmt.getFormat("#,##0.##"), boldFont);
            totalPercent = format(wb, fmt.getFormat("0.0%"), boldFont);
            totalDate = format(wb, fmt.getFormat("yyyy-mm-dd"), boldFont);
            for (CellStyle t : List.of(totalText, totalMoney, totalNumber, totalPercent, totalDate)) {
                t.setBorderTop(BorderStyle.THIN);
            }
        }

        private static CellStyle format(XSSFWorkbook wb, short dataFormat, Font font) {
            CellStyle st = wb.createCellStyle();
            st.setDataFormat(dataFormat);
            if (font != null) {
                st.setFont(font);
            }
            return st;
        }
    }
}
