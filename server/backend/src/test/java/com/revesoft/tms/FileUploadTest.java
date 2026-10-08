package com.revesoft.tms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/** Tenant documents and maintenance attachments: upload, download, type checks and isolation. */
class FileUploadTest extends ApiTestBase {

    static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 1, 2, 3};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 9, 9};

    static MockMultipartFile file(String name, String type, byte[] content) {
        return new MockMultipartFile("file", name, type, content);
    }

    private static String code(String problem) {
        return read(problem, "$.code");
    }

    private static long filesOf(Org org) throws Exception {
        Path dir = Path.of(STORAGE_DIR, org.id().toString());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void tenantDocumentsCanBeUploadedDownloadedAndDeleted() throws Exception {
        Org org = newOrg();
        String admin = org.token();
        String tenantLogin = uniq("ten");
        String tenant = createTenant(admin, "Karim", tenantLogin);

        String doc = upload(admin, "/api/tenants/" + tenant + "/documents/upload",
                file("../../nid scan.pdf", "application/pdf", PDF), "name", "NID copy", "docType", "NID").created();
        String docId = read(doc, "$.id");
        assertThat((Boolean) read(doc, "$.hasFile")).isTrue();
        assertThat((String) read(doc, "$.contentType")).isEqualTo("application/pdf");
        assertThat(((Number) read(doc, "$.sizeBytes")).intValue()).isEqualTo(PDF.length);
        assertThat((String) read(doc, "$.reference")).isEqualTo("nid scan.pdf");
        assertThat(filesOf(org)).isEqualTo(1);

        // Staff download: the same bytes, as an attachment with a safe name.
        MockHttpServletResponse file = download(admin, "/api/tenants/" + tenant + "/documents/" + docId + "/file", 200);
        assertThat(file.getContentAsByteArray()).isEqualTo(PDF);
        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getHeader("Content-Disposition")).startsWith("attachment").contains("nid scan.pdf")
                .doesNotContain("..");

        // The old JSON (reference only) endpoint still works.
        String refDoc = post(admin, "/api/tenants/" + tenant + "/documents", """
                {"name":"Agreement","docType":"AGREEMENT","reference":"Cabinet A"}""").created();
        assertThat((Boolean) read(refDoc, "$.hasFile")).isFalse();
        download(admin, "/api/tenants/" + tenant + "/documents/" + read(refDoc, "$.id") + "/file", 404);
        assertThat((List<Boolean>) read(get(admin, "/api/tenants/" + tenant).ok(), "$.documents[*].hasFile"))
                .containsExactly(true, false);

        // The tenant sees and downloads their own documents.
        String t = login(tenantLogin, PASSWORD);
        assertThat((List<String>) read(get(t, "/api/me/documents").ok(), "$[*].name")).containsExactly("NID copy", "Agreement");
        assertThat(download(t, "/api/me/documents/" + docId + "/file", 200).getContentAsByteArray()).isEqualTo(PDF);

        // Another tenant of the same landlord cannot.
        String otherLogin = uniq("ten");
        createTenant(admin, "Rahim", otherLogin);
        String other = login(otherLogin, PASSWORD);
        assertThat((List<?>) read(get(other, "/api/me/documents").ok(), "$")).isEmpty();
        download(other, "/api/me/documents/" + docId + "/file", 404);

        // Another landlord cannot.
        Org intruder = newOrg();
        download(intruder.token(), "/api/tenants/" + tenant + "/documents/" + docId + "/file", 404);
        upload(intruder.token(), "/api/tenants/" + tenant + "/documents/upload",
                file("x.pdf", "application/pdf", PDF), "name", "x", "docType", "x").andReturnBodyExpecting(404);

        // Deleting the document deletes the file.
        delete(admin, "/api/tenants/" + tenant + "/documents/" + docId).andReturnBodyExpecting(204);
        assertThat(filesOf(org)).isZero();
        download(admin, "/api/tenants/" + tenant + "/documents/" + docId + "/file", 404);
    }

    @Test
    void onlyPdfAndImagesAreAccepted() throws Exception {
        Org org = newOrg();
        String admin = org.token();
        String tenant = createTenant(admin, "Karim", null);
        String url = "/api/tenants/" + tenant + "/documents/upload";

        // Wrong declared type.
        assertThat(code(upload(admin, url, file("a.txt", "text/plain", "hello".getBytes()), "name", "a", "docType", "x")
                .andReturnBodyExpecting(400))).isEqualTo("FILE_TYPE");
        // Declared as PDF but the content is not.
        assertThat(code(upload(admin, url, file("a.pdf", "application/pdf", "MZ fake exe".getBytes()), "name", "a",
                "docType", "x").andReturnBodyExpecting(400))).isEqualTo("FILE_TYPE");
        // Declared as PNG but the content is a PDF.
        assertThat(code(upload(admin, url, file("a.png", "image/png", PDF), "name", "a", "docType", "x")
                .andReturnBodyExpecting(400))).isEqualTo("FILE_TYPE");
        // Empty file.
        assertThat(code(upload(admin, url, file("a.pdf", "application/pdf", new byte[0]), "name", "a", "docType", "x")
                .andReturnBodyExpecting(400))).isEqualTo("FILE_EMPTY");
        // Images are fine.
        String png = upload(admin, url, file("photo.png", "image/png", PNG), "name", "Photo", "docType", "PHOTO").created();
        assertThat((String) read(png, "$.contentType")).isEqualTo("image/png");
        assertThat(filesOf(org)).isEqualTo(1);
    }

    @Test
    void maintenanceRequestsCarryOneAttachment() throws Exception {
        Org org = newOrg();
        String admin = org.token();
        String property = createProperty(admin, "Green View");
        String unit1 = createUnit(admin, property, "1A", 20000);
        String unit2 = createUnit(admin, property, "1B", 20000);
        String karimLogin = uniq("karim");
        String rahimLogin = uniq("rahim");
        String karim = createTenant(admin, "Karim", karimLogin);
        String rahim = createTenant(admin, "Rahim", rahimLogin);
        activateAgreement(admin, karim, unit1, LocalDate.now().minusMonths(1), LocalDate.now().plusYears(1));
        activateAgreement(admin, rahim, unit2, LocalDate.now().minusMonths(1), LocalDate.now().plusYears(1));
        String k = login(karimLogin, PASSWORD);
        String r = login(rahimLogin, PASSWORD);

        String request = read(post(k, "/api/me/maintenance", """
                {"title":"Leaking tap","type":"PLUMBING","priority":"HIGH","description":"Kitchen sink"}""").created(),
                "$.id");
        assertThat((Boolean) read(get(k, "/api/me/maintenance/" + request).ok(), "$.hasAttachment")).isFalse();
        download(admin, "/api/maintenance/" + request + "/attachment", 404);

        // The tenant attaches a photo; staff can download it.
        String view = upload(k, "/api/me/maintenance/" + request + "/attachment", file("tap.jpg", "image/jpeg", JPEG)).ok();
        assertThat((Boolean) read(view, "$.hasAttachment")).isTrue();
        assertThat((String) read(view, "$.attachmentType")).isEqualTo("image/jpeg");
        MockHttpServletResponse photo = download(admin, "/api/maintenance/" + request + "/attachment", 200);
        assertThat(photo.getContentAsByteArray()).isEqualTo(JPEG);
        assertThat(photo.getHeader("Content-Disposition")).contains("tap.jpg");
        assertThat(download(k, "/api/me/maintenance/" + request + "/attachment", 200).getContentAsByteArray())
                .isEqualTo(JPEG);
        assertThat((List<Boolean>) read(get(admin, "/api/maintenance").ok(), "$[*].hasAttachment")).containsExactly(true);

        // Staff replace it; the old file is removed.
        upload(admin, "/api/maintenance/" + request + "/attachment", file("report.pdf", "application/pdf", PDF)).ok();
        assertThat(download(k, "/api/me/maintenance/" + request + "/attachment", 200).getContentAsByteArray())
                .isEqualTo(PDF);
        assertThat(filesOf(org)).isEqualTo(1);

        // Other tenants and other landlords cannot see or change it.
        download(r, "/api/me/maintenance/" + request + "/attachment", 404);
        upload(r, "/api/me/maintenance/" + request + "/attachment", file("x.png", "image/png", PNG))
                .andReturnBodyExpecting(404);
        Org intruder = newOrg();
        download(intruder.token(), "/api/maintenance/" + request + "/attachment", 404);
        upload(intruder.token(), "/api/maintenance/" + request + "/attachment", file("x.png", "image/png", PNG))
                .andReturnBodyExpecting(404);
        // Tenants use the portal endpoints only.
        download(k, "/api/maintenance/" + request + "/attachment", 403);
        // Wrong type.
        assertThat(code(upload(k, "/api/me/maintenance/" + request + "/attachment",
                file("x.gif", "image/gif", "GIF89a....".getBytes())).andReturnBodyExpecting(400))).isEqualTo("FILE_TYPE");
    }
}
