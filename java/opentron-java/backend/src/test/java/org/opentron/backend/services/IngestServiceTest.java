package org.opentron.backend.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentron.backend.memory.MemorySearchRequest;
import org.opentron.backend.memory.MemoryService;
import org.opentron.backend.memory.MemoryStoreRequest;
import org.opentron.backend.storage.repositories.IngestJobRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IngestServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void fileUploadIsExtractedAndStoredInSearchableMemory() throws Exception {
        MemoryService memoryService = new MemoryService(tempDir.resolve("memory.jsonl").toString());
        IngestService ingestService = new IngestService(mock(IngestJobRepository.class), memoryService);
        String jobId = "ghostbook-test-job";
        ingestService.createJob(jobId);

        Path uploadDirectory = Files.createDirectory(tempDir.resolve("uploads"));
        Files.writeString(
            uploadDirectory.resolve("journal.txt"),
            "Mara and I watched paper lanterns drift across the lake at the fair."
        );

        ingestService.processFileIngest(jobId, uploadDirectory);

        MemorySearchRequest query = new MemorySearchRequest();
        query.setQuery("What happened with Mara at the fair?");
        query.setLimit(5);

        assertEquals("completed", ingestService.getJobState(jobId));
        assertTrue(memoryService.search(query).stream()
            .anyMatch(entry -> "journal.txt".equals(entry.getMetadata().get("file_name"))));
        assertEquals("journal.txt", ingestService.searchArchive(query.getQuery(), 5).get(0).get("file_name"));
        assertEquals(1, ingestService.getJobDetails(jobId).get("chunks_created"));
    }

    @Test
    void extractsAndIndexesEverySupportedArchiveFormat() throws Exception {
        MemoryService memoryService = new MemoryService(tempDir.resolve("all-formats.jsonl").toString());
        IngestService ingestService = new IngestService(mock(IngestJobRepository.class), memoryService);
        String jobId = "ghostbook-all-formats-job";
        ingestService.createJob(jobId);

        Path uploadDirectory = Files.createDirectory(tempDir.resolve("all-formats"));
        Files.writeString(uploadDirectory.resolve("sample.csv"), "ghostbook-csv-marker");
        Files.writeString(uploadDirectory.resolve("sample.md"), "ghostbook-markdown-marker");
        Files.writeString(uploadDirectory.resolve("sample.txt"), "ghostbook-text-marker");
        writeDocx(uploadDirectory.resolve("sample.docx"), "ghostbook-docx-marker");
        writePdf(uploadDirectory.resolve("sample.pdf"), "ghostbook-pdf-marker");

        ingestService.processFileIngest(jobId, uploadDirectory);

        Map<String, Object> details = ingestService.getJobDetails(jobId);
        assertEquals("completed", details.get("status"));
        assertEquals(5, details.get("items_processed"));
        assertEquals(0, details.get("items_failed"));
        assertEquals(5, details.get("chunks_created"));

        Map<String, String> markersByFile = Map.of(
            "sample.csv", "ghostbook-csv-marker",
            "sample.docx", "ghostbook-docx-marker",
            "sample.md", "ghostbook-markdown-marker",
            "sample.pdf", "ghostbook-pdf-marker",
            "sample.txt", "ghostbook-text-marker"
        );
        for (Map.Entry<String, String> expected : markersByFile.entrySet()) {
            assertTrue(
                ingestService.searchArchive(expected.getValue(), 5).stream()
                    .anyMatch(result -> expected.getKey().equals(result.get("file_name"))),
                () -> "Expected searchable content from " + expected.getKey()
            );
        }
    }

    @Test
    void archiveEvidenceSearchExcludesNonArchiveMemory() {
        MemoryService memoryService = new MemoryService(tempDir.resolve("evidence-memory.jsonl").toString());
        IngestService ingestService = new IngestService(mock(IngestJobRepository.class), memoryService);

        MemoryStoreRequest archiveEntry = new MemoryStoreRequest();
        archiveEntry.setText("Mara met me at the lantern festival.");
        archiveEntry.setMetadata(Map.of(
            "source", "file_upload", "file_name", "journal.txt", "chunk_index", 0
        ));
        memoryService.store(archiveEntry);

        MemoryStoreRequest unrelatedEntry = new MemoryStoreRequest();
        unrelatedEntry.setText("Mara met me at the lantern festival.");
        unrelatedEntry.setMetadata(Map.of("source", "chat"));
        memoryService.store(unrelatedEntry);

        var evidence = ingestService.searchArchive("Mara lantern festival", 5);

        assertEquals(1, evidence.size());
        assertEquals("journal.txt", evidence.get(0).get("file_name"));
        assertTrue(evidence.get(0).get("excerpt").toString().contains("lantern festival"));
    }

    private void writeDocx(Path file, String text) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            writeZipEntry(output, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" "
                    + "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>");
            writeZipEntry(output, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" "
                    + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
                    + "Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            writeZipEntry(output, "word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p><w:sectPr/></w:body>"
                    + "</w:document>");
        }
    }

    private void writeZipEntry(ZipOutputStream output, String name, String content) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private void writePdf(Path file, String text) throws IOException {
        byte[] content = ("BT\n/F1 12 Tf\n72 720 Td\n(" + text + ") Tj\nET")
            .getBytes(StandardCharsets.US_ASCII);
        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.US_ASCII));
        objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>")
            .getBytes(StandardCharsets.US_ASCII));
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
            .getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream streamObject = new ByteArrayOutputStream();
        streamObject.write(("<< /Length " + content.length + " >>\nstream\n")
            .getBytes(StandardCharsets.US_ASCII));
        streamObject.write(content);
        streamObject.write("\nendstream".getBytes(StandardCharsets.US_ASCII));
        objects.add(streamObject.toByteArray());

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(pdf.size());
            pdf.write((index + 1 + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
            pdf.write(objects.get(index));
            pdf.write("\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        }

        int xrefOffset = pdf.size();
        StringBuilder xref = new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
        for (int index = 1; index < offsets.size(); index++) {
            xref.append(String.format("%010d 00000 n \n", offsets.get(index)));
        }
        xref.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n")
            .append(xrefOffset)
            .append("\n%%EOF\n");
        pdf.write(xref.toString().getBytes(StandardCharsets.US_ASCII));
        Files.write(file, pdf.toByteArray());
    }
}
