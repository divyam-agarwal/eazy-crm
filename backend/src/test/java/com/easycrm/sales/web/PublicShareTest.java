package com.easycrm.sales.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.QuotationRepository;
import com.easycrm.sales.ShareLink;
import com.easycrm.sales.ShareLinkRepository;
import com.easycrm.sales.pdf.QuotationPdfService;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class PublicShareTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    ShareLinkRepository links;

    @Autowired
    QuotationRepository quotations;

    @Autowired
    QuotationPdfService pdfService;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String sentQuotationId(String auth, String buyerName) throws Exception {
        String cId = JsonPath.read(
                mvc.perform(post("/api/v1/customers")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"businessName":"%s","stateCode":"27","source":"MANUAL"}""".formatted(buyerName)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        String pId = JsonPath.read(
                mvc.perform(post("/api/v1/products")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"sku":"SKU-%s","name":"Widget","hsnCode":"84821011","uom":"PCS",
                     "gstRate":"18","baseRate":"100.00"}""".formatted(
                                                UUID.randomUUID().toString().substring(0, 8))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        String qId = JsonPath.read(
                mvc.perform(post("/api/v1/quotations")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"10\"}]}"
                                        .formatted(cId, pId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        return qId;
    }

    private String shareToken(String auth, String quotationId) throws Exception {
        String url = JsonPath.read(
                mvc.perform(post("/api/v1/quotations/" + quotationId + "/share").header("Authorization", auth))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.publicUrl");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersTheQuotationWithNoAuthorizationHeaderAtAll() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String token = shareToken(auth, sentQuotationId(auth, "Bharat Industries"));
        TenantContext.clear();

        byte[] pdf = mvc.perform(get("/public/q/" + token)) // deliberately no header
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        // The tenant came from the share_link row, not from a JWT.
        assertTrue(textOf(pdf).contains("Bharat Industries"), textOf(pdf));
    }

    @Test
    void headRequestToAPublicLinkIsNotAuthGated() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String token = shareToken(auth, sentQuotationId(auth, "Bharat Industries"));
        TenantContext.clear();

        // Link unfurlers and some WhatsApp/proxy paths issue HEAD before GET; if that
        // fell through to denyAll() a freshly-shared link would preview as broken.
        mvc.perform(head("/public/q/" + token)).andExpect(status().isOk());
    }

    @Test
    void anUnknownTokenReturns404AndNotA401() throws Exception {
        // 401 would prove the route is auth-gated and leak that the token space exists;
        // 404 matches the codebase's cross-tenant rule. The body must be byte-identical
        // regardless of *why* the token failed - a differing message would itself confirm
        // to a holder whether their token is genuine.
        mvc.perform(get("/public/q/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("not found"))
                // A NotFoundException carries no field detail, and ApiError#fields must be
                // omitted entirely rather than serialized as "fields":null - the one thing
                // the typed error envelope (ApiErrorWireFormatTest) exists to protect,
                // asserted here against the real Boot/Jackson-3 wire, not a hand-built mapper.
                .andExpect(jsonPath("$.error.fields").doesNotExist());
        mvc.perform(get("/public/q/not-a-real-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("not found"));
    }

    @Test
    void aForgedShareLinkPointingAnotherTenantAtThisTenantsVersionIs404() throws Exception {
        // The endpoint's entire safety rests on the tenant recorded in share_link, not on
        // anything the caller supplies. This forges a row no production path can create -
        // ShareLinkService.share() always stores the caller's own TenantContext.tenantId(),
        // never an arbitrary one - to prove that a mismatched (tenant, version) pair is
        // rejected rather than rendered. Saved directly via the repository, the way
        // ShareLinkRepositoryTest exercises this global, non-@TenantId table.
        var ownerA = tokens.provisionOwner("27");
        var ownerB = tokens.provisionOwner("29");
        String qId = sentQuotationId("Bearer " + ownerA.token(), "Tenant A Buyer");
        UUID versionId = TenantContext.runAs(
                new TenantContext.TenantPrincipal(ownerA.tenantId(), null, "OWNER"),
                () -> quotations.findById(UUID.fromString(qId)).orElseThrow().getCurrentVersionId());
        TenantContext.clear();

        String forgedToken = "forged-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> links.save(new ShareLink(forgedToken, ownerB.tenantId(), versionId)));

        mvc.perform(get("/public/q/" + forgedToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("not found"));
    }

    @Test
    void renderByVersionIdThrowsNotFoundWhenNoTenantIsBoundAtAll() throws Exception {
        var owner = tokens.provisionOwner("27");
        String qId = sentQuotationId("Bearer " + owner.token(), "Bharat Industries");
        UUID versionId = TenantContext.runAs(
                new TenantContext.TenantPrincipal(owner.tenantId(), null, "OWNER"),
                () -> quotations.findById(UUID.fromString(qId)).orElseThrow().getCurrentVersionId());
        TenantContext.clear();

        // What the whole endpoint rests on, at the unit level: with no tenant installed,
        // RLS shows renderByVersionId zero rows for a version id that unambiguously exists.
        assertThrows(NotFoundException.class, () -> pdfService.renderByVersionId(versionId));
    }

    /**
     * Happy-path only: proves a tenant renders its own quotation with a second tenant's
     * rows present in the database, nothing more. It CANNOT fail if @TenantId/RLS were
     * dropped from QuotationVersion, because the version resolved here always belongs to
     * tenant A by construction - tenant B's buyer name is unreachable through it regardless
     * of isolation. The real isolation proof is
     * aForgedShareLinkPointingAnotherTenantAtThisTenantsVersionIs404 above.
     */
    @Test
    void happyPathRendersOwnQuotationWithASecondTenantsDataPresent() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String tokenA = shareToken(authA, sentQuotationId(authA, "Tenant A Buyer"));
        TenantContext.clear();

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        sentQuotationId(authB, "Tenant B Buyer");
        TenantContext.clear();

        String text = textOf(mvc.perform(get("/public/q/" + tokenA))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());

        assertTrue(text.contains("Tenant A Buyer"), text);
        assertFalse(text.contains("Tenant B Buyer"), text);
    }

    @Test
    void anAlreadySharedVersionKeepsRenderingAfterTheQuotationIsRevised() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotationId(auth, "Bharat Industries");
        String v1Token = shareToken(auth, qId);

        mvc.perform(post("/api/v1/quotations/" + qId + "/revise").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        String v2Token = shareToken(auth, qId);
        TenantContext.clear();

        assertNotEquals(v1Token, v2Token);
        // The customer who received the v1 link still sees exactly what they were sent.
        assertTrue(textOf(mvc.perform(get("/public/q/" + v1Token))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray())
                .contains("(v1)"));
        assertTrue(textOf(mvc.perform(get("/public/q/" + v2Token))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray())
                .contains("(v2)"));
    }
}
