package com.easycrm.sales;

import com.easycrm.crm.Contact;
import com.easycrm.crm.ContactRepository;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.format.IndianFormats;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.sales.web.dto.ShareResponse;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShareLinkService {

    /** 16 bytes = 128 bits of entropy: not guessable, not enumerable. */
    private static final int TOKEN_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareLinkRepository links;
    private final QuotationVersionRepository versions;
    private final ContactRepository contacts;
    private final TenantRepository tenants;
    private final VisibleFinder finder;
    private final String publicBaseUrl;

    public ShareLinkService(
            ShareLinkRepository links,
            QuotationVersionRepository versions,
            ContactRepository contacts,
            TenantRepository tenants,
            VisibleFinder finder,
            @Value("${easycrm.public-base-url}") String publicBaseUrl) {
        this.links = links;
        this.versions = versions;
        this.contacts = contacts;
        this.tenants = tenants;
        this.finder = finder;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * Token -> the tenant and version it points at. One of the app's pre-auth reads —
     * InvitationService.preview is the other, and resolves its tenant the same way.
     */
    public record Resolved(UUID tenantId, UUID quotationVersionId) {}

    @Transactional
    public ShareResponse share(UUID quotationId) {
        Quotation q = finder.findQuotation(quotationId).orElseThrow(() -> new NotFoundException("quotation not found"));
        if (q.getCurrentVersionId() == null || q.getQuoteNo() == null) {
            throw new ValidationException("status", "send the quotation before sharing it");
        }
        QuotationVersion v = versions.findById(q.getCurrentVersionId())
                .orElseThrow(() -> new NotFoundException("quotation version not found"));
        if (v.getStatus() != VersionStatus.SENT) {
            throw new ValidationException("status", "send the quotation before sharing it");
        }

        // Idempotent: reuse the version's existing link so a URL already sent to a
        // customer keeps working. This is only possible because the token is stored
        // in plaintext — see ShareLink's class comment.
        ShareLink link = links.findByQuotationVersionId(v.getId())
                .orElseGet(() -> links.save(new ShareLink(newToken(), TenantContext.tenantId(), v.getId())));

        String publicUrl = publicBaseUrl + "/public/q/" + link.getToken();
        return new ShareResponse(publicUrl, waMeUrl(q, v, publicUrl));
    }

    @Transactional(readOnly = true)
    public Resolved resolve(String token) {
        // Deliberately the same 404 for unknown and malformed: a distinguishable
        // response would confirm which tokens exist.
        ShareLink link = links.findByToken(token).orElseThrow(() -> new NotFoundException("not found"));
        return new Resolved(link.getTenantId(), link.getQuotationVersionId());
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String waMeUrl(Quotation q, QuotationVersion v, String publicUrl) {
        Tenant tenant =
                tenants.findById(TenantContext.tenantId()).orElseThrow(() -> new NotFoundException("tenant not found"));
        Optional<Contact> primary = primaryContact(q.getCustomerId());
        String number = primary.map(c -> c.getWhatsappNumber() != null ? c.getWhatsappNumber() : c.getPhone())
                .filter(s -> s != null && !s.isBlank())
                .map(ShareLinkService::digitsOnly)
                .orElse("");

        String greeting = primary.map(Contact::getName)
                .filter(n -> n != null && !n.isBlank())
                .map(n -> "Namaste " + n + ",")
                .orElse("Namaste,");
        String message = greeting
                + " please find our quotation " + q.getQuoteNo()
                + " for " + IndianFormats.rupees(v.getGrandTotal())
                + (v.getValidUntil() == null ? "" : ", valid until " + IndianFormats.date(v.getValidUntil()))
                + ".\n" + publicUrl
                + "\n- " + tenant.getBusinessName();

        // No number is not an error: wa.me with only text opens WhatsApp's contact
        // picker, which costs the salesperson one tap instead of blocking the share.
        // URLEncoder is form-urlencoding, not RFC 3986: it emits '+' for a space, which
        // a client that decodes the query string per RFC 3986 renders as a literal '+'.
        // wa.me's `text` parameter expects the RFC 3986 convention, so swap '+' for %20.
        return "https://wa.me/" + number + "?text="
                + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Optional<Contact> primaryContact(UUID customerId) {
        // findByCustomerId has no ORDER BY and nothing enforces a single primary contact,
        // so the tie-break must be explicit: primaries first, then the oldest contact.
        // Ids are UUIDv7, so id order is creation order.
        return contacts.findByCustomerId(customerId).stream()
                .min(Comparator.comparing((Contact c) -> !c.isPrimary()).thenComparing(Contact::getId));
    }

    /** wa.me wants digits only: no +, spaces or dashes. */
    private static String digitsOnly(String phone) {
        return phone.replaceAll("\\D", "");
    }
}
