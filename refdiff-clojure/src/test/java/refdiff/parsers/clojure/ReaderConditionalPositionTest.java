package refdiff.parsers.clojure;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import refdiff.core.cst.*;
import refdiff.core.io.SourceFolder;

public class ReaderConditionalPositionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void conditionalMarkersHaveExactOriginalOffsets() {
        for (String newline : new String[] {"\n", "\r\n"}) {
            String source = "; 😀 é" + newline + "#?(:clj x :cljs #?@(:cljs [y]))";
            List<TokenPosition> tokens = new RewriteCljTokenizer().tokenize(new ClojureSourceText(source));
            List<String> values = new ArrayList<>();
            int previous = 0;
            for (TokenPosition t : tokens) {
                assertTrue(t.getStart() >= previous);
                assertTrue(t.getEnd() > t.getStart());
                values.add(source.substring(t.getStart(), t.getEnd()));
                previous = t.getEnd();
            }
            assertEquals(Arrays.asList(";", "😀", "é", "#?", "(", ":clj", "x", ":cljs", "#?@", "(", ":cljs", "[", "y", "]", ")", ")"), values);
            assertEquals(source.indexOf("#?"), tokens.get(3).getStart());
            assertEquals(source.indexOf("#?") + 2, tokens.get(3).getEnd());
            assertEquals(source.indexOf("#?@") + 3, tokens.get(8).getEnd());
        }
    }

    @Test public void conditionalWhitespaceAndOrdinaryTaggedLiteralsArePreserved() {
        String source = "#? (:clj #inst \"2020\") #?@\n(:cljs [])";
        List<String> values = new ArrayList<>();
        for (TokenPosition t : new RewriteCljTokenizer().tokenize(new ClojureSourceText(source))) {
            values.add(source.substring(t.getStart(), t.getEnd()));
        }
        assertEquals(Arrays.asList("#", "?", "(", ":clj", "#", "inst", "\"2020\"", ")", "#?@", "(", ":cljs", "[", "]", ")"), values);
    }

    @Test public void pluginPreservesDeclarationsAndAritySpans() throws Exception {
        for (String newline : new String[] {"\n", "\r\n"}) {
            String body = "#?(:clj x :cljs (str x))";
            String declaration = "(defn ^:private f ([x] " + body + ") ([x y] [x y]))";
            String source = "; 😀" + newline + "(ns demo)" + newline + declaration;
            Path dir = temporary.newFolder().toPath();
            Files.write(dir.resolve("demo.cljc"), source.getBytes(StandardCharsets.UTF_8));
            CstNode file = new ClojurePlugin().parse(SourceFolder.from(dir, ".cljc")).getNodes().get(0);
            assertEquals(3, file.getNodes().size());
            CstNode ns = file.getNodes().get(0), f = file.getNodes().get(1);
            assertEquals("NamespaceDeclaration", ns.getType());
            assertEquals("(ns demo)", source.substring(ns.getLocation().getBegin(), ns.getLocation().getEnd()));
            assertSame(file, f.getParent().get());
            assertEquals("f/1", f.getLocalName());
            assertEquals(declaration, source.substring(f.getLocation().getBegin(), f.getLocation().getEnd()));
            ClojureFunctionLocation loc = (ClojureFunctionLocation) f.getLocation();
            assertEquals("([x] " + body + ")", source.substring(loc.getArityBegin(), loc.getArityEnd()));
            assertEquals(body, source.substring(loc.getBodyBegin(), loc.getBodyEnd()));
            assertEquals("f/2", file.getNodes().get(2).getLocalName());
        }
    }

    @Test(expected = RuntimeException.class) public void malformedConditionalStillFails() {
        new RewriteCljTokenizer().tokenize(new ClojureSourceText("#?(:clj ["));
    }
}
