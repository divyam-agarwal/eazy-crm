package com.easycrm.sales.web;

import com.easycrm.platform.error.NotFoundException;
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
        ShareLinkService.Resolved resolved = shareLinks.resolve(token); // 404 if unknown
        byte[] pdf;
        try {
            pdf = TenantContext.runAs(
                    new TenantContext.TenantPrincipal(resolved.tenantId(), null, "PUBLIC"),
                    () -> pdfService.renderByVersionId(resolved.quotationVersionId()));
        } catch (NotFoundException e) {
            // Uniform 404 for every failure on this route. Without this, a token whose
            // recorded version can't be loaded under its recorded tenant (data corruption,
            // or a forged share_link row) would surface renderByVersionId's own message
            // ("quotation version not found") instead of resolve()'s ("not found") -
            // and a holder could use the differing body to confirm their token is real.
            throw new NotFoundException("not found");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                // A share URL pasted somewhere crawlable (a forum, a public channel) should
                // not end up indexed - it's a live link to one customer's priced quotation.
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(pdf);
    }
}
