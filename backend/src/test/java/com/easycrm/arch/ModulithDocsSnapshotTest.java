package com.easycrm.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.EasyCrmApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * The C4 component diagrams and module canvases are generated from bytecode, committed, and
 * guarded here — the same pattern as OpenApiSnapshotTest, for the same reason: generated
 * documentation that is not diff-checked drifts exactly the way the service-scope doc drifted
 * (its own §3.2 records prose that was stale the day it was written).
 *
 * <p>Byte-stability was verified before this was written: 16 files, 0 differing across two
 * consecutive runs (MA3). What that spike did NOT vary was the JUnit run context. This test
 * <b>found</b> a real instability while it was being built: {@code Documenter} emits each
 * PlantUML component diagram's {@code Rel(...)} lines — and its {@code Component(...)}
 * declarations, covered for the same reason after a review flagged them — from internal, unordered
 * collections, so their line order (never their content) reliably differs between an isolated
 * single-test run
 * (e.g. {@code ./gradlew updateModulithDocs}) and a full-suite run ({@code ./gradlew clean check}
 * or {@code test}) on the very same machine and JDK, and is reproducible within each of those
 * contexts. {@link #canonicalize} sorts just those two families of lines before either branch runs, which is
 * enough to make every one of the 16 files byte-identical across both contexts again — verified by
 * running the guard from both {@code updateModulithDocs} and {@code clean check} repeatedly.
 *
 * <p>That finding narrows, but does not retire, the risk this guard was already known to carry
 * (WA4): byte-stability was only ever checked on one machine, and this test canonicalizes the one
 * source of disorder found here, not a proof that {@code Documenter} has no other one. If CI's
 * bytes ever differ from a developer's after this, treat it the same way — find and canonicalize
 * the specific unordered collection — before concluding the guard is unfixable and should become
 * report-only rather than being quietly deleted.
 *
 * <p>Run {@code ./gradlew updateModulithDocs} to rewrite the committed output instead of asserting
 * against it. Deliberately this same test in a second mode, not a second generator.
 */
class ModulithDocsSnapshotTest {

    /**
     * Floor on the generated file count, checked BEFORE the write/read branch so both modes get it.
     * Without it, a Documenter that produced nothing would let write mode blank the committed docs
     * and pass, and read mode would then compare nothing against nothing and pass too. Every other
     * assertion here compares the output to itself; this one compares it to a fact about the app.
     */
    private static final int MINIMUM_FILES = 10;

    @Test
    void generatedDocsMatchTheCommittedSnapshot() throws IOException {
        Path committed = Path.of(System.getProperty("modulith.docs"));
        Path generated = Files.createTempDirectory("modulith-docs");

        ApplicationModules modules = ApplicationModules.of(EasyCrmApplication.class);
        new Documenter(modules, Documenter.Options.defaults().withOutputFolder(generated.toString()))
                .writeDocumentation();

        List<Path> files = relativeFiles(generated);
        assertThat(files.size())
                .as("Documenter produced almost nothing; the guard would compare nothing to nothing")
                .isGreaterThanOrEqualTo(MINIMUM_FILES);

        canonicalize(files, generated);

        if (Boolean.getBoolean("modulith.docs.write")) {
            if (Files.exists(committed)) deleteRecursively(committed);
            Files.createDirectories(committed);
            for (Path rel : files) {
                Path target = committed.resolve(rel.toString());
                Files.createDirectories(target.getParent());
                Files.copy(generated.resolve(rel), target);
            }
            System.out.println("modulith: wrote " + files.size() + " files to " + committed);
            return;
        }

        assertThat(Files.isDirectory(committed))
                .as("%s is missing -- run ./gradlew updateModulithDocs and commit the result", committed)
                .isTrue();

        assertThat(relativeFiles(committed))
                .as("the set of generated documentation files changed -- regenerate with "
                        + "./gradlew updateModulithDocs and commit it with the change that moved it")
                .containsExactlyInAnyOrderElementsOf(files);

        for (Path rel : files) {
            assertThat(Files.mismatch(generated.resolve(rel), committed.resolve(rel)))
                    .as("%s drifted from the committed copy; regenerate with " + "./gradlew updateModulithDocs", rel)
                    .isEqualTo(-1L);
        }
    }

    private static List<Path> relativeFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> out = new ArrayList<>();
            walk.filter(Files::isRegularFile).sorted().forEach(p -> out.add(root.relativize(p)));
            return out;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * Sorts each contiguous run of {@code Rel(...)} and of {@code Component(...)} lines in every
     * {@code .puml} file in place. Both render from internal, unordered collections: edges and a
     * container's components respectively. Their relative order carries no meaning (a set, not a
     * sequence), but their
     * as-emitted order is exactly what varies between an isolated single-test JVM and a full-suite
     * one, and {@link Files#mismatch} does not know that. Sorting is the canonicalization, applied
     * identically before the write/read branch so both modes compare (and commit) the same bytes
     * regardless of which run produced them. Every other line, and every {@code .adoc} file, is
     * left untouched -- nothing else was observed to reorder.
     */
    private static void canonicalize(List<Path> files, Path root) throws IOException {
        for (Path rel : files) {
            if (!rel.toString().endsWith(".puml")) continue;
            Path file = root.resolve(rel);
            // Read and rewrite unconditionally, preserving this file's own trailing-newline-or-not
            // convention explicitly: rewriting only when the Rel block actually needed reordering
            // made that trailing byte depend on whether sorting was a no-op, which is exactly the
            // kind of incidental difference this method exists to remove, not introduce.
            String content = Files.readString(file, StandardCharsets.UTF_8);
            boolean trailingNewline = content.endsWith("\n");
            List<String> sorted = canonicalizeUnorderedRuns(content.lines().toList());
            String rewritten = String.join("\n", sorted) + (trailingNewline ? "\n" : "");
            Files.writeString(file, rewritten, StandardCharsets.UTF_8);
        }
    }

    /**
     * Sorts each contiguous run of {@code Rel(...)} lines and each contiguous run of {@code
     * Component(...)} lines in every {@code .puml} file in place. Both render from the same family
     * of internal, unordered/insertion-ordered structurizr collections: {@code Rel(...)} lines are
     * Modulith's inter-module dependency edges, {@code Component(...)} lines are a container's
     * member modules, and in neither case does relative order carry meaning -- each is an edge or
     * member <em>set</em>, not a sequence. That makes sorting lossless: it can only change the byte
     * order of a set that was never ordered in the first place. Without it, {@link Files#mismatch}
     * cannot distinguish a meaningless reshuffle of one of these sets from real content drift, so
     * canonicalizing before comparison removes that ambiguity rather than papering over it.
     *
     * <p>{@code Rel(...)} lines sit unindented at file scope; {@code Component(...)} lines sit
     * indented 4 spaces inside a {@code Container_Boundary}. Matching is done on the trimmed line
     * for the latter and the raw line for the former so the two families are told apart and each
     * run is sorted only among lines of its own kind -- a run of one kind immediately followed by a
     * run of the other is never merged into a single sorted block. Every other line, and every
     * {@code .adoc} file, is left untouched -- nothing else was observed to reorder.
     */
    private static List<String> canonicalizeUnorderedRuns(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            String kind = unorderedRunKind(lines.get(i));
            if (kind == null) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            List<String> block = new ArrayList<>();
            while (i < lines.size() && kind.equals(unorderedRunKind(lines.get(i)))) {
                block.add(lines.get(i));
                i++;
            }
            Collections.sort(block);
            out.addAll(block);
        }
        return out;
    }

    /**
     * Classifies a line as belonging to the {@code Rel(...)} family, the {@code Component(...)}
     * family, or neither (returning {@code null}). Used to group only contiguous lines of the same
     * family into one sortable run.
     */
    private static String unorderedRunKind(String line) {
        if (line.startsWith("Rel(")) return "REL";
        if (line.trim().startsWith("Component(")) return "COMPONENT";
        return null;
    }
}
