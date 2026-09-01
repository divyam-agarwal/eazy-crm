package com.easycrm.sales.pdf;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class QuotationPdfRendererTest extends IntegrationTest {

    @Autowired
    QuotationPdfRenderer renderer;

    private static QuotationPdfData data(boolean interState) {
        return new QuotationPdfData(
                new QuotationPdfData.Seller(
                        "Acme Traders",
                        "27AAPFU0939F1ZV",
                        "12 MG Road, Pune 411001",
                        "+919876543210",
                        "sales@acme.example"),
                new QuotationPdfData.Buyer("Bharat Industries", "29AAPFU0939F1ZV", "44 Brigade Road, Bengaluru 560001"),
                new QuotationPdfData.Doc(
                        "QTN/2026-27/0001",
                        1,
                        "28-07-2026",
                        "27-08-2026",
                        "29",
                        "30 days",
                        "Ex-works",
                        "Rates valid for this order only"),
                List.of(new QuotationPdfData.Line(
                        1,
                        "Ball Bearing 6203",
                        "84821011",
                        "PCS",
                        "10",
                        "Rs. 100.00",
                        "5",
                        "18",
                        "Rs. 950.00",
                        "Rs. 1,121.00")),
                new QuotationPdfData.Totals(
                        "Rs. 950.00",
                        interState ? null : "Rs. 85.50",
                        interState ? null : "Rs. 85.50",
                        interState ? "Rs. 171.00" : null,
                        "Rs. 171.00",
                        "Rs. 1,121.00"),
                interState,
                Instant.parse("2026-07-28T10:15:30Z"));
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void showsBothPartiesTheQuoteNumberAndEveryLine() throws Exception {
        String text = textOf(renderer.render(data(true)));

        assertTrue(text.contains("Acme Traders"), text);
        assertTrue(text.contains("12 MG Road, Pune 411001"), text);
        assertTrue(text.contains("Bharat Industries"), text);
        assertTrue(text.contains("QTN/2026-27/0001"), text);
        assertTrue(text.contains("Ball Bearing 6203"), text);
        assertTrue(text.contains("84821011"), text); // HSN is a GST requirement
        assertTrue(text.contains("Rs. 1,121.00"), text);
        assertTrue(text.contains("30 days"), text);
    }

    @Test
    void interStateShowsIgstAndNeverCgstOrSgst() throws Exception {
        String text = textOf(renderer.render(data(true)));

        assertTrue(text.contains("IGST"), text);
        assertFalse(text.contains("CGST"), text);
        assertFalse(text.contains("SGST"), text);
    }

    @Test
    void intraStateShowsCgstAndSgstAndNeverIgst() throws Exception {
        String text = textOf(renderer.render(data(false)));

        assertTrue(text.contains("CGST"), text);
        assertTrue(text.contains("SGST"), text);
        assertFalse(text.contains("IGST"), text);
    }

    @Test
    void aSellerWithNoAddressPhoneOrEmailStillRenders() throws Exception {
        QuotationPdfData full = data(false);
        QuotationPdfData sparse = new QuotationPdfData(
                new QuotationPdfData.Seller("Acme Traders", null, null, null, null),
                full.buyer(),
                full.doc(),
                full.lines(),
                full.totals(),
                full.interState(),
                full.renderTimestamp());

        String text = textOf(renderer.render(sparse));

        assertTrue(text.contains("Acme Traders"), text);
        assertFalse(text.contains("null"), text); // no null leaking onto the letterhead
    }

    @Test
    void theSameVersionAlwaysRendersToTheSameBytes() {
        assertArrayEquals(renderer.render(data(false)), renderer.render(data(false)));
    }

    @Test
    void theDocumentIsTitledWithTheQuoteNumberForTheBrowserTab() throws Exception {
        try (PDDocument doc = PDDocument.load(renderer.render(data(false)))) {
            assertEquals("QTN/2026-27/0001 (v1)", doc.getDocumentInformation().getTitle());
        }
    }
}
