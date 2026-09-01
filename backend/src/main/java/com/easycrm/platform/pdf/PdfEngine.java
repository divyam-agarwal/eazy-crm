package com.easycrm.platform.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Component;

/**
 * Renders well-formed XHTML to PDF bytes. Knows nothing about any domain object.
 *
 * Output is a pure function of (xhtml, timestamp): the caller supplies the timestamp
 * so that re-rendering the same frozen quotation version always produces identical
 * bytes, which is what makes "shown, emailed and WhatsApped output are the same
 * document" an assertable property rather than an aspiration.
 */
@Component
public class PdfEngine {

    public byte[] render(String xhtml, Instant timestamp) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(xhtml, null);
            builder.toStream(raw);
            builder.useFastMode();
            builder.run();
        } catch (Exception e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
        return stampDeterministicMetadata(raw.toByteArray(), timestamp);
    }

    /**
     * Replaces the wall-clock creation date and the writer's random document ID with
     * values derived from the caller's timestamp, so two renders of the same input
     * are byte-identical.
     */
    private byte[] stampDeterministicMetadata(byte[] pdf, Instant timestamp) {
        Calendar at = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        at.setTimeInMillis(timestamp.toEpochMilli());
        try (PDDocument doc = PDDocument.load(pdf)) {
            // Start from what openhtmltopdf already wrote -- notably /Title, which it
            // maps from the XHTML <title> element -- and overwrite only the fields that
            // are otherwise a source of nondeterminism (producer, creator, the two
            // dates). Replacing the whole PDDocumentInformation object would silently
            // drop /Title and any other metadata the template supplies.
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setProducer("EasyCRM");
            info.setCreator("EasyCRM");
            info.setCreationDate(at);
            info.setModificationDate(at);
            // openhtmltopdf's own first-pass writer already stamped a random /ID pair
            // into the trailer. PDFBox's COSWriter only recomputes /ID via MD5 when the
            // trailer does NOT already carry a 2-element ID array; otherwise it silently
            // keeps the inherited (random) one, so setDocumentId() alone has no effect.
            // Removing the inherited entry forces PDFBox onto its deterministic path,
            // which hashes this pinned document id together with the Info dictionary
            // above -- and that dictionary is itself a pure function of `timestamp`.
            doc.getDocument().getTrailer().removeItem(COSName.ID);
            doc.setDocumentId(timestamp.toEpochMilli());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF metadata stamping failed", e);
        }
    }
}
