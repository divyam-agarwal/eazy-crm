package com.easycrm.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every hand-thrown master-data validation or conflict error must carry a reason code, so a client
 * can translate it instead of showing English prose (docs/api/error-codes.md rule 3).
 *
 * <p>This is source inspection, not ArchUnit, and deliberately so: ArchUnit sees types and call
 * edges, never constructor <em>arguments</em>, so it cannot distinguish
 * {@code new ValidationException(field, message)} from
 * {@code new ValidationException(field, message, CODE)}. Do not "upgrade" this to an ArchUnit rule;
 * it would silently stop checking anything.
 *
 * <p><b>Two call shapes are recognised.</b> The direct shape passes the code as a literal, e.g.
 * {@code new ValidationException("field", "message", "CODE")} or
 * {@code new ConflictException(msg, fields, Map.of("field", "CODE"))} -- detected by requiring the
 * <em>last</em> quoted string literal anywhere in the constructor arguments to look like
 * {@code SCREAMING_SNAKE}. Checking only the last literal (not "any literal in the call", which is
 * what an earlier draft of this guard did) matters: an all-caps, underscore-bearing *message*
 * quoted earlier in the argument list must not be mistaken for a code. The accumulator shape,
 * used by {@code ProductService.validate}, builds two {@code Map<String,String>} locals across
 * several {@code if} blocks and throws {@code new ValidationException(errors, codes)} with no
 * literal in the call at all -- detected by taking the final bare-identifier argument and
 * confirming it is populated somewhere earlier in the file via {@code codes.put(key, "CODE")}.
 * Verified empirically (2026-09-24) that no other {@code SCREAMING_SNAKE}-shaped string literal of
 * 4+ characters appears anywhere in the five guarded files, so scanning per-file rather than
 * per-method for the accumulator's {@code .put} calls does not currently admit noise; if a future
 * guarded file needs an unrelated all-caps string constant, this will need tightening to a
 * method-scoped search.
 */
class MasterDataErrorCodesTest {

    /**
     * F1-4's five hand-thrown call sites: the four master-data services from Tasks 5/6, plus
     * {@code AssignableUsers}, which Task 5 also gave a fielded code ({@code ASSIGNEE_INVALID}) even
     * though it lives outside {@code crm}/{@code catalog} -- the one code added outside those two
     * packages is exactly the one a file list scoped to only "the four services" would miss.
     */
    private static final List<Path> GUARDED = List.of(
            Path.of("src/main/java/com/easycrm/crm/CustomerService.java"),
            Path.of("src/main/java/com/easycrm/catalog/ProductService.java"),
            Path.of("src/main/java/com/easycrm/catalog/PriceListService.java"),
            Path.of("src/main/java/com/easycrm/catalog/PriceListItemService.java"),
            Path.of("src/main/java/com/easycrm/iam/AssignableUsers.java"));

    /** `new ValidationException(` or `new ConflictException(` through to the closing paren. */
    private static final Pattern THROW_SITE =
            Pattern.compile("new (ValidationException|ConflictException)\\s*\\(([^;]*?)\\)\\s*;", Pattern.DOTALL);

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern SCREAMING_SNAKE = Pattern.compile("[A-Z][A-Z0-9_]+");

    @Test
    void everyGuardedFileExists() {
        // Non-vacuity: a renamed or moved service must fail loudly, not quietly stop being checked.
        for (Path p : GUARDED) {
            assertTrue(Files.exists(p), "guarded file is missing -- update GUARDED: " + p);
        }
    }

    @Test
    void theGuardActuallyFindsThrowSites() {
        // Non-vacuity: if the regex stops matching, every other assertion here passes for free.
        int found = 0;
        for (Path p : GUARDED) {
            found += countMatches(read(p));
        }
        // Measured 2026-09-24 across the five guarded files after Tasks 5/6: 12 throw sites
        // (CustomerService 3, ProductService 2, PriceListService 2, PriceListItemService 4,
        // AssignableUsers 1). The brief's original floor (>= 9) predated Tasks 5/6 and the addition
        // of AssignableUsers; pinning it to the measured count means deleting even one guarded
        // throw site fails this test, not just a mass deletion.
        assertTrue(found >= 12, "expected to find the known throw sites, found " + found);
    }

    @Test
    void noFieldedErrorIsThrownWithoutACode() {
        List<String> offenders = new ArrayList<>();
        for (Path p : GUARDED) {
            String src = read(p);
            Matcher m = THROW_SITE.matcher(src);
            while (m.find()) {
                List<String> codes = codesFor(m.group(2), src.substring(0, m.start()));
                if (codes.isEmpty()) {
                    offenders.add(p.getFileName() + ": new " + m.group(1) + "(" + oneLine(m.group(2)) + ")");
                }
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "master-data errors thrown without a reason code:\n" + String.join("\n", offenders));
    }

    @Test
    void everyCodeThrownIsRegistered() throws IOException {
        String registry = Files.readString(Path.of("../docs/api/error-codes.md"));
        List<String> unregistered = new ArrayList<>();
        for (Path p : GUARDED) {
            String src = read(p);
            Matcher m = THROW_SITE.matcher(src);
            while (m.find()) {
                for (String c : codesFor(m.group(2), src.substring(0, m.start()))) {
                    if (!registry.contains("`" + c + "`")) unregistered.add(c);
                }
            }
        }
        assertTrue(unregistered.isEmpty(), "codes thrown but absent from docs/api/error-codes.md: " + unregistered);
    }

    @Test
    void theRegistryCheckCanFail() {
        // Non-vacuity for the assertion above: prove the registry is actually being read and that a
        // bogus code would not be found in it.
        assertFalse(readRegistryQuietly().contains("`DEFINITELY_NOT_A_REAL_CODE`"));
    }

    /**
     * The reason code(s), if any, that this throw site carries -- empty means "no code", i.e. an
     * offender. See the class javadoc for the two shapes this recognises.
     */
    private static List<String> codesFor(String args, String textBeforeThrow) {
        List<String> quoted = new ArrayList<>();
        Matcher q = QUOTED.matcher(args);
        while (q.find()) quoted.add(q.group(1));

        if (!quoted.isEmpty()) {
            // Direct shape: the code, if present, is the LAST quoted literal in the call -- not
            // just any quoted literal, so an all-caps *message* quoted earlier in the argument
            // list is never mistaken for a code.
            String last = quoted.get(quoted.size() - 1);
            if (!SCREAMING_SNAKE.matcher(last).matches()) {
                return List.of();
            }
            List<String> codes = new ArrayList<>();
            for (String s : quoted) {
                if (SCREAMING_SNAKE.matcher(s).matches()) codes.add(s);
            }
            return codes;
        }

        // Accumulator shape: no literal at all in the call, e.g. `new ValidationException(errors,
        // codes)`. The last bare-identifier argument is the codes map; confirm it is actually
        // populated with a SCREAMING_SNAKE code earlier in the same file.
        String[] parts = args.split(",");
        if (parts.length == 0) return List.of();
        String lastArg = parts[parts.length - 1].trim();
        if (!lastArg.matches("[A-Za-z_][A-Za-z0-9_]*")) return List.of();

        Pattern populate =
                Pattern.compile(Pattern.quote(lastArg) + "\\.put\\(\\s*\"[^\"]*\"\\s*,\\s*\"([A-Z][A-Z0-9_]+)\"");
        Matcher put = populate.matcher(textBeforeThrow);
        List<String> codes = new ArrayList<>();
        while (put.find()) codes.add(put.group(1));
        return codes;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    private static String readRegistryQuietly() {
        return read(Path.of("../docs/api/error-codes.md"));
    }

    private static int countMatches(String src) {
        Matcher m = THROW_SITE.matcher(src);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
