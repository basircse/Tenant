package com.revesoft.tms.storage;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** A stored file ready to stream to the client as an attachment. */
public record FileDownload(Resource resource, String contentType, long sizeBytes, String fileName) {

    public ResponseEntity<Resource> toResponse() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(sizeBytes)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(fileName))
                .body(resource);
    }

    /** {@code attachment; filename="..."} (plus the RFC 5987 UTF-8 form for non-ASCII names). */
    public static String attachment(String fileName) {
        boolean ascii = StandardCharsets.US_ASCII.newEncoder().canEncode(fileName);
        return ContentDisposition.attachment()
                .filename(fileName, ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8)
                .build().toString();
    }

    /** Bytes generated on the fly (reports, receipts). */
    public static ResponseEntity<byte[]> bytes(byte[] body, String contentType, String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(fileName))
                .body(body);
    }
}
