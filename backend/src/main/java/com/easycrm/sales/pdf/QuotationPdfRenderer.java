package com.easycrm.sales.pdf;

import com.easycrm.platform.pdf.PdfEngine;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Component
public class QuotationPdfRenderer {

    private final TemplateEngine templates;
    private final PdfEngine pdf;

    public QuotationPdfRenderer(TemplateEngine templates, PdfEngine pdf) {
        this.templates = templates;
        this.pdf = pdf;
    }

    public byte[] render(QuotationPdfData data) {
        // Locale.ROOT: nothing in the template is localised, and pinning it keeps the
        // output independent of the server's default locale.
        Context ctx = new Context(Locale.ROOT);
        ctx.setVariable("d", data);
        String xhtml = templates.process("quotation", ctx);
        return pdf.render(xhtml, data.renderTimestamp());
    }
}
