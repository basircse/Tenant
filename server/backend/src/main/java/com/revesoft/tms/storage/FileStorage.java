package com.revesoft.tms.storage;

import com.revesoft.tms.common.BusinessException;
import com.revesoft.tms.security.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/**
 * Keeps uploaded files on disk under {@code <tms.storage.dir>/<org id>/<random id>}. The client's
 * file name is never used in the path. Only PDF, JPEG, PNG and WebP are accepted, checked both by the
 * declared content type and by the file's first bytes.
 *
 * <p>Inside a transaction, a saved file is removed again if the transaction rolls back, and
 * {@link #deleteAfterCommit} only removes a file once the database change is committed.
 */
@Component
public class FileStorage {

    public static final String FILE_TYPE = "FILE_TYPE";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String FILE_EMPTY = "FILE_EMPTY";

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    /** Allowed content type → file extension used for download names. */
    private static final Map<String, String> ALLOWED = Map.of(
            "application/pdf", "pdf",
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final Path root;
    private final long maxBytes;

    public FileStorage(@Value("${tms.storage.dir:./data/files}") String dir,
                       @Value("${tms.storage.max-file-size:10MB}") DataSize maxSize) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.maxBytes = maxSize.toBytes();
    }

    /** Validates and stores an upload for the current organisation. */
    public StoredFile save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, FILE_EMPTY, "Choose a file to upload");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, FILE_TOO_LARGE,
                    "The file is too large (maximum " + (maxBytes / (1024 * 1024)) + " MB)");
        }
        String declared = normalizeType(file.getContentType());
        byte[] head = new byte[16];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String sniffed = sniff(Arrays.copyOf(head, read));
        if (declared == null || !ALLOWED.containsKey(declared) || !declared.equals(sniffed)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, FILE_TYPE,
                    "Only PDF, JPEG, PNG or WebP files can be uploaded");
        }
        UUID org = TenantContext.requireOrg();
        String key = org + "/" + UUID.randomUUID();
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Stored file {} ({} bytes, {})", key, file.getSize(), file.getContentType());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the file", e);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        log.debug("Transaction rolled back; removing stored file {}", key);
                        delete(key);
                    }
                }
            });
        }
        return new StoredFile(key, declared, file.getSize(), cleanName(file.getOriginalFilename()));
    }

    /** Opens a stored file of the current organisation. */
    public FileSystemResource open(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw BusinessException.notFound("File");
        }
        return new FileSystemResource(path);
    }

    /** Download wrapper with a safe file name (falls back to {@code baseName.ext}). */
    public FileDownload download(String key, String contentType, Long size, String originalName, String baseName) {
        FileSystemResource resource = open(key);
        long length = size != null ? size : resource.getFile().length();
        return new FileDownload(resource, contentType, length, downloadName(originalName, baseName, contentType));
    }

    public void delete(String key) {
        if (key == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stored file {}", key, e);
        }
    }

    /** Deletes the file once the surrounding transaction commits (immediately without one). */
    public void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID org = TenantContext.current();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    TenantContext.callAs(org, () -> {
                        delete(key);
                        return null;
                    });
                }
            });
        } else {
            delete(key);
        }
    }

    // ---------------------------------------------------------------- Helpers

    /** Keys are "<org uuid>/<uuid>" and must belong to the current organisation. */
    private Path resolve(String key) {
        String[] parts = key == null ? new String[0] : key.split("/");
        if (parts.length != 2) {
            throw BusinessException.notFound("File");
        }
        UUID org;
        UUID id;
        try {
            org = UUID.fromString(parts[0]);
            id = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            throw BusinessException.notFound("File");
        }
        UUID current = TenantContext.current();
        if (!TenantContext.ROOT.equals(current) && !org.equals(current)) {
            throw BusinessException.notFound("File");
        }
        return root.resolve(org.toString()).resolve(id.toString());
    }

    private static String normalizeType(String contentType) {
        if (contentType == null) {
            return null;
        }
        String t = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(t) || "image/pjpeg".equals(t) ? "image/jpeg" : t;
    }

    /** Content type from the file signature ("magic bytes"), or null when unknown. */
    static String sniff(byte[] b) {
        if (startsWith(b, 0x25, 0x50, 0x44, 0x46, 0x2D)) { // %PDF-
            return "application/pdf";
        }
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (b.length >= 12 && startsWith(b, 0x52, 0x49, 0x46, 0x46) // RIFF....WEBP
                && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] b, int... sig) {
        if (b.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if ((b[i] & 0xFF) != sig[i]) {
                return false;
            }
        }
        return true;
    }

    /** Keeps only the last path segment, without control or special characters, max 255 chars. */
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\p{Cntrl}\"<>|:*?;]", "").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            return null;
        }
        return n.length() > 255 ? n.substring(n.length() - 255) : n;
    }

    /** A safe download name with the extension matching the stored content type. */
    static String downloadName(String originalName, String baseName, String contentType) {
        String ext = ALLOWED.getOrDefault(contentType, "bin");
        String name = cleanName(originalName);
        if (name == null) {
            String base = baseName == null ? "" : baseName.replaceAll("[^\\p{L}\\p{N} ._-]", "").trim();
            name = (base.isEmpty() ? "file" : base) + "." + ext;
        } else {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith("." + ext) && !(ext.equals("jpg") && lower.endsWith(".jpeg"))) {
                name = name + "." + ext;
            }
        }
        return name;
    }
}
