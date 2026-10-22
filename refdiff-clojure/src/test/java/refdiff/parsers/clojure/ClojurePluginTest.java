package refdiff.parsers.clojure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import refdiff.core.cst.CstNode;
import refdiff.core.cst.CstNodeRelationship;
import refdiff.core.cst.CstNodeRelationshipType;
import refdiff.core.cst.CstRoot;
import refdiff.core.cst.Location;
import refdiff.core.cst.Stereotype;
import refdiff.core.cst.TokenizedSource;
import refdiff.core.diff.CstComparator;
import refdiff.core.diff.CstDiff;
import refdiff.core.diff.CstRootHelper;
import refdiff.core.diff.RelationshipType;
import refdiff.core.io.FilePathFilter;
import refdiff.core.io.SourceFolder;

public class ClojurePluginTest {

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private final ClojurePlugin plugin = new ClojurePlugin();

	@Test
	public void shouldParseClojureFile() throws Exception {
		Path basePath = Paths.get("test-data/parser/clojure");
		SourceFolder sources = SourceFolder.from(basePath, Paths.get("simple.clj"));
		String sourceCode = sources.readContent(sources.getSourceFiles().get(0));

		CstRoot root = plugin.parse(sources);

		assertEquals(1, root.getNodes().size());
		CstNode fileNode = root.getNodes().get(0);
		assertEquals("File", fileNode.getType());
		assertEquals("simple.clj", fileNode.getLocalName());
		assertEquals("simple.clj", fileNode.getSimpleName());
		assertEquals("", fileNode.getNamespace());
		assertEquals(new Location("simple.clj", 0, sourceCode.length(), 1, 0, sourceCode.length()),
			fileNode.getLocation());
		assertTrue(fileNode.hasStereotype(Stereotype.HAS_BODY));

		TokenizedSource tokenizedSource = root.getTokenizedSource().get("simple.clj");
		assertNotNull(tokenizedSource);
		assertTrue(tokenizedSource.getTokens().length > 0);

		List<String> tokens = CstRootHelper.retrieveTokens(root, sourceCode, fileNode, false);
		assertEquals(Arrays.asList("(", "ns", "demo.core", ")", "(", "defn", "greet", "[", "name", "]",
			"(", "str", "\"Hello, \"", "name", ")", ")"), tokens);
	}

	@Test
	public void shouldUseFileNamespaceAndSupportAllowedExtensions() throws Exception {
		Path basePath = Paths.get("test-data/parser/clojure");
		SourceFolder sources = SourceFolder.from(basePath, Paths.get("dir1/simple.cljs"));

		CstRoot root = plugin.parse(sources);
		CstNode fileNode = root.getNodes().get(0);

		assertEquals("dir1/", fileNode.getNamespace());
		assertEquals("simple.cljs", fileNode.getLocalName());
		assertEquals("simple.cljs", fileNode.getSimpleName());

		FilePathFilter filter = plugin.getAllowedFilesFilter();
		assertTrue(filter.isAllowed("a.clj"));
		assertTrue(filter.isAllowed("a.cljs"));
		assertTrue(filter.isAllowed("a.cljc"));
		assertFalse(filter.isAllowed("a.edn"));
		assertFalse(filter.isAllowed("a.java"));
	}

	@Test
	public void shouldWorkWithCstComparator() throws Exception {
		Path basePath = Paths.get("test-data/parser/clojure");
		SourceFolder sources = SourceFolder.from(basePath, Paths.get("simple.clj"));

		CstDiff diff = new CstComparator(plugin).compare(sources, sources);

		assertFalse(diff.getRelationships().isEmpty());
		assertTrue(diff.getRefactoringRelationships().isEmpty());
		assertEquals(RelationshipType.SAME, diff.getRelationships().iterator().next().getType());
	}

	@Test
	public void shouldExtractNamespaceAndFunctionDeclarations() throws Exception {
		Path basePath = Paths.get("test-data/parser/clojure");
		SourceFolder sources = SourceFolder.from(basePath, Paths.get("functions.clj"));
		String source = sources.readContent(sources.getSourceFiles().get(0));

		CstRoot root = plugin.parse(sources);
		CstNode fileNode = root.getNodes().get(0);
		List<CstNode> functions = fileNode.getNodes();

		assertEquals(10, functions.size());
		assertEquals(Arrays.asList("greet/1", "hidden/1", "multi/1", "multi/2", "multi/2+",
			"destructured/2+", "no-body/0", "typed/0", "annotated/0", "wrapped/1"),
			functions.stream().map(CstNode::getLocalName).collect(Collectors.toList()));
		for (CstNode function : functions) {
			assertEquals("FunctionDeclaration", function.getType());
			assertEquals("demo.core.", function.getNamespace());
			assertTrue(function.hasStereotype(Stereotype.HAS_BODY));
			assertSame(fileNode, function.getParent().get());
		}

		CstNode greet = functionByLocalName(fileNode, "greet/1");
		assertEquals("greet", greet.getSimpleName());
		assertEquals(Arrays.asList("name"), parameterNames(greet));
		assertEquals(6, greet.getLocation().getLine());
		assertEquals(source.indexOf("(defn greet"), greet.getLocation().getBegin());
		int hiddenStart = source.indexOf("\n\n(defn- hidden");
		assertEquals(hiddenStart, greet.getLocation().getEnd());
		assertFunctionBody(source, greet, "(str \"Hello, \" name)\n  (str \"Bye, \" name)");

		CstNode hidden = functionByLocalName(fileNode, "hidden/1");
		assertEquals("hidden", hidden.getSimpleName());
		assertEquals(Arrays.asList("x"), parameterNames(hidden));

		int multiStart = source.indexOf("(defn multi");
		int destructuredStart = source.indexOf("\n\n(defn destructured");
		CstNode multi1 = functionByLocalName(fileNode, "multi/1");
		CstNode multi2 = functionByLocalName(fileNode, "multi/2");
		CstNode multi3 = functionByLocalName(fileNode, "multi/2+");
		for (CstNode arity : Arrays.asList(multi1, multi2, multi3)) {
			assertEquals("multi", arity.getSimpleName());
			assertEquals(multiStart, arity.getLocation().getBegin());
			assertEquals(destructuredStart, arity.getLocation().getEnd());
		}
		assertEquals(Arrays.asList("x"), parameterNames(multi1));
		assertEquals(Arrays.asList("x", "y"), parameterNames(multi2));
		assertEquals(Arrays.asList("x", "y", "more"), parameterNames(multi3));
		assertFunctionBody(source, multi1, "(str x)");
		assertFunctionBody(source, multi2, "(+ x y)");
		assertFunctionBody(source, multi3, "(apply + x y more)");

		CstNode destructured = functionByLocalName(fileNode, "destructured/2+");
		assertEquals(Arrays.asList("{:keys [a b]}", "[c d]", "rest"), parameterNames(destructured));
		assertFunctionBody(source, destructured, "[a b c d rest]");

		CstNode noBody = functionByLocalName(fileNode, "no-body/0");
		assertTrue(noBody.getParameters().isEmpty());
		assertFunctionBody(source, noBody, "");

		CstNode typed = functionByLocalName(fileNode, "typed/0");
		assertEquals("typed", typed.getSimpleName());
		assertEquals(Arrays.asList(), parameterNames(typed));
		assertFunctionBody(source, typed, "\"value\"");

		CstNode annotated = functionByLocalName(fileNode, "annotated/0");
		assertEquals("annotated", annotated.getSimpleName());
		assertEquals(Arrays.asList(), parameterNames(annotated));

		CstNode wrapped = functionByLocalName(fileNode, "wrapped/1");
		assertEquals("wrapped", wrapped.getSimpleName());
		int wrappedStart = source.indexOf("^:private");
		assertEquals(wrappedStart, wrapped.getLocation().getBegin());
		assertEquals(source.indexOf("\n", wrappedStart), wrapped.getLocation().getEnd());
		assertFunctionBody(source, wrapped, "z");
	}

	@Test
	public void shouldAcceptNamespaceWithoutFunctions() throws Exception {
		Path basePath = Paths.get("test-data/parser/clojure");
		SourceFolder sources = SourceFolder.from(basePath, Paths.get("namespace_only.clj"));

		CstRoot root = plugin.parse(sources);

		assertEquals(1, root.getNodes().size());
		assertTrue(root.getNodes().get(0).getNodes().isEmpty());
		assertNotNull(root.getTokenizedSource().get("namespace_only.clj"));
	}

	@Test
	public void shouldUseEmptyNamespaceWhenNamespaceIsMissing() throws Exception {
		Path basePath = Paths.get("test-data/parser/clojure");
		SourceFolder sources = SourceFolder.from(basePath, Paths.get("no_namespace.clj"));
		String source = sources.readContent(sources.getSourceFiles().get(0));

		CstRoot root = plugin.parse(sources);
		CstNode function = root.getNodes().get(0).getNodes().get(0);

		assertEquals("standalone", function.getSimpleName());
		assertEquals("standalone/1", function.getLocalName());
		assertEquals("", function.getNamespace());
		assertEquals(0, function.getLocation().getBegin());
		assertEquals(source.lastIndexOf(")") + 1, function.getLocation().getEnd());
		assertEquals(1, function.getLocation().getLine());
		assertFunctionBody(source, function, "(inc x)");
	}

	@Test
	public void shouldPreserveOffsetsForCrLfAndLoneCr() throws Exception {
		String[] lineEndings = {"\r\n", "\r"};

		for (int i = 0; i < lineEndings.length; i++) {
			Path directory = temporaryFolder.newFolder("line-ending-" + i).toPath();
			String source = "(ns demo.core)" + lineEndings[i] + "(defn f [x]" + lineEndings[i]
				+ "  (inc x))" + lineEndings[i];
			Files.write(directory.resolve("a.clj"), source.getBytes(StandardCharsets.UTF_8));
			SourceFolder sources = SourceFolder.from(directory, Paths.get("a.clj"));

			CstRoot root = plugin.parse(sources);
			CstNode function = root.getNodes().get(0).getNodes().get(0);

			assertEquals("demo.core.", function.getNamespace());
			assertEquals(source.indexOf("(defn f"), function.getLocation().getBegin());
			assertEquals(source.lastIndexOf(")") + 1, function.getLocation().getEnd());
			assertEquals(2, function.getLocation().getLine());
			assertFunctionBody(source, function, "(inc x)");
		}
	}

	@Test
	public void shouldResolveUseRelationships() throws Exception {
		CstRoot root = parseUses("uses/main_uses.clj", "uses/lib.clj");

		CstNode target = functionInFile(root, "uses/main_uses.clj", "target/1");
		CstNode libTarget = functionInFile(root, "uses/lib.clj", "target/1");
		CstNode other = functionInFile(root, "uses/lib.clj", "other/1");
		CstNode onlyGlobal = functionInFile(root, "uses/lib.clj", "only-global/1");
		CstNode caller = functionInFile(root, "uses/main_uses.clj", "caller/1");
		CstNode nested = functionInFile(root, "uses/main_uses.clj", "nested/1");
		CstNode recursive = functionInFile(root, "uses/main_uses.clj", "recursive/1");
		CstNode multi0 = functionInFile(root, "uses/main_uses.clj", "multi/0");
		CstNode multi1 = functionInFile(root, "uses/main_uses.clj", "multi/1");
		CstNode multi2 = functionInFile(root, "uses/main_uses.clj", "multi/2");
		CstNode callMulti2 = functionInFile(root, "uses/main_uses.clj", "call-multi-2/0");

		assertUse(root, caller, target);
		assertNoUse(root, caller, libTarget);

		assertUse(root, nested, target);
		assertEquals(1L, countUses(root, nested, target));

		assertUse(root, recursive, recursive);

		assertUse(root, multi0, multi1);
		assertUse(root, callMulti2, multi2);
		assertNoUse(root, multi0, multi2);

		CstNode variadicTarget = functionInFile(root, "uses/main_uses.clj", "variadic-target/1+");
		CstNode callVariadic = functionInFile(root, "uses/main_uses.clj", "call-variadic/3");
		CstNode callVariadicTooFew = functionInFile(root, "uses/main_uses.clj", "call-variadic-too-few/0");
		assertUse(root, callVariadic, variadicTarget);
		assertNoUse(root, callVariadicTooFew, variadicTarget);

		assertUse(root, functionInFile(root, "uses/main_uses.clj", "qualified/0"), other);
		assertUse(root, functionInFile(root, "uses/main_uses.clj", "aliased/0"), other);
		assertUse(root, functionInFile(root, "uses/main_uses.clj", "global-only/0"), onlyGlobal);

		CstNode higherOrder = functionInFile(root, "uses/main_uses.clj", "higher-order/1");
		CstNode higherOrderList = functionInFile(root, "uses/main_uses.clj", "higher-order-list/1");
		assertNoUse(root, higherOrder, target);
		assertUse(root, higherOrderList, target);

		CstNode missing = functionInFile(root, "uses/main_uses.clj", "missing/0");
		assertNoOutgoingUse(root, missing);

		CstNode duplicate = functionInFile(root, "uses/main_uses.clj", "duplicate/0");
		assertEquals(1L, countUses(root, duplicate, target));

		CstNode shadowed = functionInFile(root, "uses/main_uses.clj", "shadowed/1");
		CstNode letShadowed = functionInFile(root, "uses/main_uses.clj", "let-shadowed/0");
		assertNoUse(root, shadowed, target);
		assertNoUse(root, letShadowed, target);

		assertEquals(11, root.getRelationships().size());
	}

	@Test
	public void shouldIgnoreAmbiguousGlobalCalls() throws Exception {
		CstRoot root = parseUses("uses/ambiguous_a.clj", "uses/ambiguous_b.clj",
			"uses/ambiguous_caller.clj");

		assertTrue(root.getRelationships().isEmpty());
	}

	@Test
	public void shouldResolveFunctionInAnotherFileOfTheSameNamespace() throws Exception {
		CstRoot root = parseUses("uses/shared_a.clj", "uses/shared_b.clj");

		CstNode target = functionInFile(root, "uses/shared_a.clj", "shared-target/1");
		CstNode caller = functionInFile(root, "uses/shared_b.clj", "shared-caller/1");

		assertUse(root, caller, target);
		assertEquals(1, root.getRelationships().size());
	}

	private CstRoot parseUses(String... paths) throws Exception {
		Path basePath = Paths.get("test-data/parser/clojure");
		List<Path> files = Arrays.stream(paths).map(path -> Paths.get(path)).collect(Collectors.toList());
		return plugin.parse(SourceFolder.from(basePath, files));
	}

	private static CstNode functionInFile(CstRoot root, String filePath, String localName) {
		for (CstNode fileNode : root.getNodes()) {
			if (filePath.equals(fileNode.getLocation().getFile())) {
				return functionByLocalName(fileNode, localName);
			}
		}
		throw new AssertionError("File not found: " + filePath);
	}

	private static void assertUse(CstRoot root, CstNode caller, CstNode callee) {
		CstNodeRelationship relationship = new CstNodeRelationship(
			CstNodeRelationshipType.USE, caller.getId(), callee.getId());
		assertTrue("Expected USE " + caller.getLocalName() + " -> " + callee.getLocalName(),
			root.getRelationships().contains(relationship));
	}

	private static void assertNoUse(CstRoot root, CstNode caller, CstNode callee) {
		CstNodeRelationship relationship = new CstNodeRelationship(
			CstNodeRelationshipType.USE, caller.getId(), callee.getId());
		assertFalse("Unexpected USE " + caller.getLocalName() + " -> " + callee.getLocalName(),
			root.getRelationships().contains(relationship));
	}

	private static long countUses(CstRoot root, CstNode caller, CstNode callee) {
		return root.getRelationships().stream()
			.filter(relationship -> relationship.getType() == CstNodeRelationshipType.USE)
			.filter(relationship -> relationship.getN1() == caller.getId() && relationship.getN2() == callee.getId())
			.count();
	}

	private static void assertNoOutgoingUse(CstRoot root, CstNode caller) {
		assertFalse("Unexpected outgoing USE from " + caller.getLocalName(),
			root.getRelationships().stream()
				.anyMatch(relationship -> relationship.getType() == CstNodeRelationshipType.USE
					&& relationship.getN1() == caller.getId()));
	}

	private static CstNode functionByLocalName(CstNode fileNode, String localName) {
		for (CstNode node : fileNode.getNodes()) {
			if (localName.equals(node.getLocalName())) {
				return node;
			}
		}
		throw new AssertionError("Function not found: " + localName);
	}

	private static List<String> parameterNames(CstNode function) {
		return function.getParameters().stream().map(parameter -> parameter.getName()).collect(Collectors.toList());
	}

	private static void assertFunctionBody(String source, CstNode function, String expected) {
		Location location = function.getLocation();
		assertEquals(expected, source.substring(location.getBodyBegin(), location.getBodyEnd()));
	}
}
