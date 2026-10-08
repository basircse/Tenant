package com.revesoft.tms.report;

import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.report.ReportService.Filter;
import com.revesoft.tms.report.ReportService.ReportInfo;
import com.revesoft.tms.report.ReportService.Result;
import com.revesoft.tms.storage.FileDownload;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/reports/{type}?format=json|xlsx|pdf&from=&to=&month=&propertyId=}. The JSON form is a
 * {@link ReportTable}; xlsx and pdf are downloads rendered from the same table.
 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class ReportController {

    private final ReportService service;
    private final XlsxRenderer xlsx;
    private final PdfRenderer pdf;

    public ReportController(ReportService service, XlsxRenderer xlsx, PdfRenderer pdf) {
        this.service = service;
        this.xlsx = xlsx;
        this.pdf = pdf;
    }

    @GetMapping
    public List<ReportInfo> types() {
        return service.types();
    }

    @GetMapping("/{type}")
    public ResponseEntity<?> report(@PathVariable String type,
                                    @RequestParam(defaultValue = "json") String format,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) YearMonth month,
                                    @RequestParam(required = false) UUID propertyId) {
        String fmt = format.toLowerCase(Locale.ROOT);
        if (!fmt.equals("json") && !fmt.equals("xlsx") && !fmt.equals("pdf")) {
            throw new BusinessException("format must be json, xlsx or pdf");
        }
        Result r = service.build(type, new Filter(from, to, month, propertyId));
        return switch (fmt) {
            case "xlsx" -> FileDownload.bytes(xlsx.render(r.table(), r.orgName(), r.generatedAt()),
                    XlsxRenderer.CONTENT_TYPE, r.fileBaseName() + ".xlsx");
            case "pdf" -> FileDownload.bytes(pdf.render(r.table(), r.orgName(), r.generatedAt()),
                    PdfRenderer.CONTENT_TYPE, r.fileBaseName() + ".pdf");
            default -> ResponseEntity.ok(r.table());
        };
    }
}
