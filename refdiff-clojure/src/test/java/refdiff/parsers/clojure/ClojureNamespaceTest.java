package refdiff.parsers.clojure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.JsonNode;

import refdiff.cli.RunClojureCommitJson;
import refdiff.core.cst.CstNode;
import refdiff.core.cst.CstRoot;
import refdiff.core.diff.CstComparator;
import refdiff.core.diff.CstDiff;
import refdiff.core.diff.RelationshipType;
import refdiff.core.io.SourceFolder;

public class ClojureNamespaceTest {

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private final ClojurePlugin plugin = new ClojurePlugin();

	@Test
	public void shouldPreserveNamespaceSpanAndSerializeItsIdentity() throws Exception {
		for (String newline : new String[] {"\n", "\r\n"}) {
			String declaration = "^:private (ns ^:no-doc demo.core\n"
				+ "  \"Documentação 😀\"\n"
				+ "  {:author \"é\"}\n"
				+ "  ;; dependências\n"
				+ "  (:require [lib.core :as lib]))";
			declaration = declaration.replace("\n", newline);
			String source = "; 😀 comentário" + newline + declaration + newline + "(defn f [x] x)";
			SourceFolder sources = sources(source);
			CstNode file = plugin.parse(sources).getNodes().get(0);
			CstNode namespace = file.getNodes().get(0);
			assertEquals("NamespaceDeclaration", namespace.getType());
			assertEquals("demo.core", namespace.getSimpleName());
			assertEquals("ns demo.core", namespace.getLocalName());
			assertEquals("", namespace.getNamespace());
			assertEquals(2, namespace.getLocation().getLine());
			assertEquals(source.indexOf("^:private"), namespace.getLocation().getBegin());
			assertEquals(declaration, source.substring(namespace.getLocation().getBegin(), namespace.getLocation().getEnd()));
			assertEquals(source.indexOf(newline + "(defn"), namespace.getLocation().getEnd());
			assertSame(file, namespace.getParent().get());
			assertEquals("demo.core.", file.getNodes().get(1).getNamespace());

			Method serialize = RunClojureCommitJson.class.getDeclaredMethod("entities", CstRoot.class);
			serialize.setAccessible(true);
			JsonNode entities = (JsonNode) serialize.invoke(null, plugin.parse(sources));
			JsonNode json = null;
			for (JsonNode entity : entities) {
				if ("NamespaceDeclaration".equals(entity.get("type").asText())) {
					json = entity;
				}
			}
			assertNotNull(json);
			assertEquals("ns demo.core", json.get("signature").asText());
			assertEquals(file.getId(), json.get("parent_id").asInt());
			assertEquals(namespace.getLocation().getBegin(), json.get("location").get("begin").asInt());
			assertEquals(namespace.getLocation().getEnd(), json.get("location").get("end").asInt());
		}
	}

	@Test
	public void shouldIgnoreQuotedDiscardedCommentedAndNestedNamespaceForms() throws Exception {
		String source = "; (ns commented)\n#_(ns discarded)\n'(ns quoted)\n"
			+ "`(ns syntax-quoted)\n(comment (ns nested))\n[ns data]\n(defn f [] :ok)";
		CstNode file = plugin.parse(sources(source)).getNodes().get(0);
		assertEquals(1, file.getNodes().size());
		assertEquals("FunctionDeclaration", file.getNodes().get(0).getType());
		assertEquals("", file.getNodes().get(0).getNamespace());
	}

	@Test
	public void shouldKeepMatchesWhenOnlyRequiresChange() throws Exception {
		CstDiff diff = compare("(ns demo (:require [lib.core :refer [a b c]]))\n(defn f [x] (inc x))",
			"(ns demo (:require [lib.core :as lib]))\n(defn f [x] (inc x))");
		assertEquals(3, diff.getRelationships().size());
		assertTrue(diff.getRelationships().stream().allMatch(r -> r.getType() == RelationshipType.SAME));
		assertTrue(diff.getRefactoringRelationships().isEmpty());
	}

	@Test
	public void shouldRenameNamespaceWithoutReparentingFunctions() throws Exception {
		String tail = " (:require [lib.core :as lib] [other.core :refer [helper]]))\n(defn f [x] (inc x))";
		CstDiff diff = compare("(ns old.name" + tail, "(ns new.name" + tail);
		assertEquals(3, diff.getRelationships().size());
		assertEquals(1, diff.getRefactoringRelationships().size());
		assertEquals("NamespaceDeclaration", diff.getRefactoringRelationships().iterator().next().getNodeBefore().getType());
		assertEquals(RelationshipType.RENAME, diff.getRefactoringRelationships().iterator().next().getType());
		assertTrue(diff.getRelationships().stream().anyMatch(r ->
			"f/1".equals(r.getNodeBefore().getLocalName()) && r.getType() == RelationshipType.SAME));
	}

	@Test
	public void shouldKeepDistinctNamespacesPairedWhenReordered() throws Exception {
		CstDiff diff = compare("(ns first.ns)\n(ns second.ns)\n(defn f [] :ok)",
			"(ns second.ns)\n(ns first.ns)\n(defn f [] :ok)");
		List<CstNode> namespaces = diff.getBefore().getNodes().get(0).getNodes().stream()
			.filter(n -> "NamespaceDeclaration".equals(n.getType())).collect(Collectors.toList());
		assertEquals(2, namespaces.size());
		assertFalse(namespaces.get(0).hasStereotype(refdiff.core.cst.Stereotype.HAS_BODY));
		assertEquals(4, diff.getRelationships().size());
		assertTrue(diff.getRelationships().stream().allMatch(r -> r.getType() == RelationshipType.SAME));
	}

	@Test
	public void shouldNotMatchNamespaceWithVarOfTheSameName() throws Exception {
		CstDiff diff = compare("(ns demo)", "(def demo :value)");
		assertEquals(1, diff.getRelationships().size());
		assertEquals("File", diff.getRelationships().iterator().next().getNodeBefore().getType());
		assertTrue(diff.getRefactoringRelationships().isEmpty());
	}

	private CstDiff compare(String before, String after) throws Exception {
		return new CstComparator(plugin).compare(sources(before), sources(after));
	}

	private SourceFolder sources(String source) throws Exception {
		Path folder = temporaryFolder.newFolder().toPath();
		Files.write(folder.resolve("a.clj"), source.getBytes(StandardCharsets.UTF_8));
		return SourceFolder.from(folder, java.nio.file.Paths.get("a.clj"));
	}
}
