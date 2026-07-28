package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.ShareLinkService;
import com.easycrm.sales.pdf.QuotationPdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application's only unauthenticated read path.
 *
 * A public request has no JWT, so it has no tenant, so every tenant-scoped query would
 * return zero rows. The share_link table is global precisely so this one lookup can
 * happen without a tenant; the tenant it yields is then installed via runAs BEFORE the
 * rendering transaction opens, and everything after that is ordinary @TenantId + RLS
 * loading. The token is never echoed into a response or a log.
 */
@RestController
@RequestMapping("/public/q")
public class PublicShareController {

    private final ShareLinkService shareLinks;
    private final QuotationPdfService pdfService;

    public PublicShareController(ShareLinkService shareLinks, QuotationPdfService pdfService) {
        this.shareLinks = shareLinks;
        this.pdfService = pdfService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> quotation(@PathVariable String token) {
        ShareLinkService.Resolved resolved = shareLinks.resolve(token);   // 404 if unknown
        byte[] pdf = TenantContext.runAs(
            new TenantContext.TenantPrincipal(resolved.tenantId(), null, "PUBLIC"),
            () -> pdfService.renderByVersionId(resolved.quotationVersionId()));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(pdf);
    }
}
