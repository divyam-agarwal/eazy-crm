package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * openapi-typescript keys response types by media type, so a response keyed '*' + '/' + '*'
 * gives the generated frontend client an untyped body. This reads the COMMITTED snapshot, which
 * OpenApiSnapshotTest already holds equal to what springdoc generates.
 *
 * <p>Non-vacuity: the guard also asserts JSON responses exist in quantity and that both PDF routes
 * are keyed application/pdf — a document that parsed to nothing, or a walker that visited nothing,
 * cannot pass.
 */
class OpenApiMediaTypesTest {

    private static final String ANY = "*/*";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() throws Exception {
        String yaml = Files.readString(Path.of(System.getProperty("openapi.snapshot")), StandardCharsets.UTF_8);
        Map<String, Object> doc = new Yaml().load(yaml);
        return (Map<String, Object>) doc.get("paths");
    }

    /** Every (path, method, status, mediaType) the document declares. */
    @SuppressWarnings("unchecked")
    private static List<String[]> responseMediaTypes() throws Exception {
        List<String[]> out = new ArrayList<>();
        for (var path : paths().entrySet()) {
            for (var op : ((Map<String, Object>) path.getValue()).entrySet()) {
                if (!(op.getValue() instanceof Map<?, ?> opMap)) continue;
                Object responses = opMap.get("responses");
                if (!(responses instanceof Map<?, ?> resMap)) continue;
                for (var res : resMap.entrySet()) {
                    Object content = ((Map<String, Object>) res.getValue()).get("content");
                    if (!(content instanceof Map<?, ?> contentMap)) continue;
                    for (Object mediaType : contentMap.keySet()) {
                        out.add(new String[] {
                            path.getKey(), op.getKey(), String.valueOf(res.getKey()), (String) mediaType
                        });
                    }
                }
            }
        }
        return out;
    }

    @Test
    void noResponseIsKeyedAnyMediaType() throws Exception {
        List<String> offenders = responseMediaTypes().stream()
                .filter(r -> ANY.equals(r[3]))
                .map(r -> r[1].toUpperCase() + " " + r[0] + " " + r[2])
                .toList();
        assertTrue(
                offenders.isEmpty(),
                offenders.size() + " responses keyed */*, e.g. "
                        + offenders.stream().limit(5).toList());
    }

    @Test
    void jsonResponsesExistInQuantity() throws Exception {
        long json = responseMediaTypes().stream()
                .filter(r -> "application/json".equals(r[3]))
                .count();
        assertTrue(json >= 400, "only " + json + " application/json responses; the walker or document is broken");
    }

    @Test
    void bothPdfRoutesDeclareApplicationPdfOnSuccess() throws Exception {
        for (String route : List.of("/api/v1/quotations/{id}/pdf", "/public/q/{token}")) {
            List<String> ok = responseMediaTypes().stream()
                    .filter(r -> r[0].equals(route) && r[1].equals("get") && r[2].equals("200"))
                    .map(r -> r[3])
                    .toList();
            assertEquals(List.of("application/pdf"), ok, route + " 200 response media types");
        }
    }
}
