package com.easycrm.platform.pdf;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfEngineTest {

    private static final String XHTML = """
        <html><head><style>body { font-family: Helvetica; }</style></head>
        <body><h1>Quotation QTN/2026-27/0001</h1><p>Rs. 1,23,456.78</p></body></html>
        """;

    private final PdfEngine engine = new PdfEngine();

    @Test
    void rendersXhtmlToAPdfContainingTheText() throws Exception {
        byte[] pdf = engine.render(XHTML, Instant.parse("2026-07-28T10:15:30Z"));

        assertTrue(pdf.length > 0);
        // Every PDF starts with the %PDF- header; this proves we produced a real file.
        assertEquals("%PDF-", new String(pdf, 0, 5));

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("QTN/2026-27/0001"), text);
            assertTrue(text.contains("Rs. 1,23,456.78"), text);
        }
    }

    @Test
    void preservesTitleFromXhtmlHeadIntoDocumentInformation() throws Exception {
        String xhtmlWithTitle = """
            <html><head><title>Quotation QTN/2026-27/0001</title>
            <style>body { font-family: Helvetica; }</style></head>
            <body><h1>Quotation QTN/2026-27/0001</h1></body></html>
            """;

        byte[] pdf = engine.render(xhtmlWithTitle, Instant.parse("2026-07-28T10:15:30Z"));

        try (PDDocument doc = PDDocument.load(pdf)) {
            // Task 8 serves these PDFs inline in a browser, where /Title becomes the
            // tab name; the metadata-stamping step must not discard it while pinning
            // the deterministic fields (producer, creator, dates).
            assertEquals(
                    "Quotation QTN/2026-27/0001", doc.getDocumentInformation().getTitle());
        }
    }

    @Test
    void sameInputRendersToIdenticalBytes() {
        Instant at = Instant.parse("2026-07-28T10:15:30Z");

        byte[] first = engine.render(XHTML, at);
        byte[] second = engine.render(XHTML, at);

        // The design spec requires shown/emailed/WhatsApped output to be byte-identical.
        // PDF writers stamp a creation date and a document ID by default, which would
        // make these differ; render() must pin both.
        assertArrayEquals(first, second);
    }
}
