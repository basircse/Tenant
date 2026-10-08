package com.revesoft.tms.report;

import com.revesoft.tms.common.BusinessClock;
import com.revesoft.tms.payment.PaymentService.Receipt;
import com.revesoft.tms.storage.FileDownload;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Money receipt as a PDF download (staff and tenant portal). */
@Component
public class ReceiptPdf {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PdfRenderer pdf;
    private final BusinessClock clock;

    public ReceiptPdf(PdfRenderer pdf, BusinessClock clock) {
        this.pdf = pdf;
        this.clock = clock;
    }

    public ResponseEntity<byte[]> response(Receipt receipt) {
        String generatedAt = LocalDateTime.ofInstant(clock.now(), clock.zone()).format(STAMP);
        String name = "receipt-" + receipt.receiptNo().replaceAll("[^A-Za-z0-9-]", "") + ".pdf";
        return FileDownload.bytes(pdf.receipt(receipt, generatedAt), PdfRenderer.CONTENT_TYPE, name);
    }
}
