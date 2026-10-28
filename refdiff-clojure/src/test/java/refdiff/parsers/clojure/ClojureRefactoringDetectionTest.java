package refdiff.parsers.clojure;

import static org.junit.Assert.assertThat;
import static refdiff.test.util.CstDiffMatchers.containsOnly;
import static refdiff.test.util.CstDiffMatchers.node;
import static refdiff.test.util.CstDiffMatchers.relationship;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import org.junit.Test;

import refdiff.core.cst.CstNode;
import refdiff.core.diff.CstComparator;
import refdiff.core.diff.CstComparatorMonitor;
import refdiff.core.diff.CstDiff;
import refdiff.core.diff.Relationship;
import refdiff.core.diff.RelationshipType;
import refdiff.core.io.SourceFolder;

public class ClojureRefactoringDetectionTest {

	private final ClojurePlugin plugin = new ClojurePlugin();

	@Test
	public void shouldDetectFunctionRename() throws Exception {
		CstDiff diff = diff("renameFunction");
		printDiff("renameFunction", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.rename.keep/1"),
				node("a.clj", "demo.rename.keep/1")),
			relationship(RelationshipType.RENAME,
				node("a.clj", "demo.rename.old-name/1"),
				node("a.clj", "demo.rename.new-name/1"))
		));
	}

	@Test
	public void shouldNotMatchAmbiguousRenameCandidates() throws Exception {
		CstDiff diff = diff("ambiguousRename");
		printDiff("ambiguousRename", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj"))
		));
	}

	@Test
	public void shouldNotMatchRenameWithDifferentBodies() throws Exception {
		CstDiff diff = diff("differentBodyRename");
		printDiff("differentBodyRename", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.different.keep-a/1"),
				node("a.clj", "demo.different.keep-a/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.different.keep-b/1"),
				node("a.clj", "demo.different.keep-b/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.different.keep-c/1"),
				node("a.clj", "demo.different.keep-c/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.different.keep-d/1"),
				node("a.clj", "demo.different.keep-d/1"))
		));
	}

	@Test
	public void shouldMatchRenamePreservingArity() throws Exception {
		CstDiff diff = diff("multiArityRename");
		printDiff("multiArityRename", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.multi.keep-a/1"),
				node("a.clj", "demo.multi.keep-a/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.multi.keep-b/1"),
				node("a.clj", "demo.multi.keep-b/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.multi.keep-c/1"),
				node("a.clj", "demo.multi.keep-c/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.multi.keep-d/1"),
				node("a.clj", "demo.multi.keep-d/1")),
			relationship(RelationshipType.RENAME,
				node("a.clj", "demo.multi.old-fn/1"),
				node("a.clj", "demo.multi.new-fn/1")),
			relationship(RelationshipType.RENAME,
				node("a.clj", "demo.multi.old-fn/2"),
				node("a.clj", "demo.multi.new-fn/2"))
		));
	}

	@Test
	public void shouldNotMatchRenameWithTrivialBody() throws Exception {
		CstDiff diff = diff("trivialBodyRename");
		printDiff("trivialBodyRename", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.trivial.keep-a/1"),
				node("a.clj", "demo.trivial.keep-a/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.trivial.keep-b/1"),
				node("a.clj", "demo.trivial.keep-b/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.trivial.keep-c/1"),
				node("a.clj", "demo.trivial.keep-c/1")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.trivial.keep-d/1"),
				node("a.clj", "demo.trivial.keep-d/1"))
		));
	}

	@Test
	public void shouldDetectFunctionMoveBetweenFiles() throws Exception {
		CstDiff diff = diff("moveFunctionFile");
		printDiff("moveFunctionFile", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.SAME, node("b.clj"), node("b.clj")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.move.caller/1"),
				node("a.clj", "demo.move.caller/1")),
			relationship(RelationshipType.MOVE,
				node("a.clj", "demo.move.helper/1"),
				node("b.clj", "demo.move.helper/1"))
		));
	}

	@Test
	public void shouldDetectFunctionMoveBetweenNamespaces() throws Exception {
		CstDiff diff = diff("moveFunctionNamespace");
		printDiff("moveFunctionNamespace", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.SAME, node("b.clj"), node("b.clj")),
			relationship(RelationshipType.MOVE,
				node("a.clj", "demo.ns.a.helper/1"),
				node("b.clj", "demo.ns.b.helper/1"))
		));
	}

	@Test
	public void shouldDetectArityChange() throws Exception {
		CstDiff diff = diff("changeArity");
		printDiff("changeArity", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.CHANGE_SIGNATURE,
				node("a.clj", "demo.arity.add/1"),
				node("a.clj", "demo.arity.add/2"))
		));
	}

	@Test
	public void shouldDetectExtractFunction() throws Exception {
		CstDiff diff = diff("extractFunction");
		printDiff("extractFunction", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.extract.caller/1"),
				node("a.clj", "demo.extract.caller/1")),
			relationship(RelationshipType.EXTRACT,
				node("a.clj", "demo.extract.caller/1"),
				node("a.clj", "demo.extract.inc-one/1"))
		));
	}

	@Test
	public void shouldDetectInlineFunction() throws Exception {
		CstDiff diff = diff("inlineFunction");
		printDiff("inlineFunction", diff);

		assertThat(diff, containsOnly(
			relationship(RelationshipType.SAME, node("a.clj"), node("a.clj")),
			relationship(RelationshipType.SAME,
				node("a.clj", "demo.inline.caller/1"),
				node("a.clj", "demo.inline.caller/1")),
			relationship(RelationshipType.INLINE,
				node("a.clj", "demo.inline.inc-one/1"),
				node("a.clj", "demo.inline.caller/1"))
		));
	}

	private CstDiff diff(String scenario) throws Exception {
		Path basePath = Paths.get("test-data/refactorings", scenario);
		SourceFolder sourcesBefore = SourceFolder.from(basePath.resolve("v0"), ".clj");
		SourceFolder sourcesAfter = SourceFolder.from(basePath.resolve("v1"), ".clj");
		CstComparatorMonitor monitor = new CstComparatorMonitor() {
			@Override
			public void reportMatchDiscardedBySimilarity(CstNode n1, CstNode n2, double score, double threshold) {
				System.out.println(String.format("DISCARD_BY_SIMILARITY %s -> %s score=%.4f threshold=%.4f",
					n1.getLocalName(), n2.getLocalName(), score, threshold));
			}

			@Override
			public void reportMatchDiscardedByConflict(CstNode nBefore, CstNode nAfter) {
				System.out.println(String.format("DISCARD_BY_CONFLICT %s -> %s",
					nBefore.getLocalName(), nAfter.getLocalName()));
			}

			@Override
			public void reportExtractDiscardedBySimilarity(CstNode n1, CstNode n2, double score, double threshold) {
				System.out.println(String.format("EXTRACT_DISCARD %s -> %s score=%.4f threshold=%.4f",
					n1.getLocalName(), n2.getLocalName(), score, threshold));
			}

			@Override
			public void reportInlineDiscardedBySimilarity(CstNode n1, CstNode n2, double score, double threshold) {
				System.out.println(String.format("INLINE_DISCARD %s -> %s score=%.4f threshold=%.4f",
					n1.getLocalName(), n2.getLocalName(), score, threshold));
			}
		};
		return new CstComparator(plugin).compare(sourcesBefore, sourcesAfter, monitor);
	}

	private static void printDiff(String scenario, CstDiff diff) {
		System.out.println("=== " + scenario);
		diff.getRelationships().stream()
			.sorted(Comparator.comparing((Relationship r) -> r.getType().name())
				.thenComparing(r -> r.getNodeBefore().getLocation().getFile())
				.thenComparing(r -> r.getNodeBefore().getLocalName()))
			.forEach(r -> System.out.println(r.getDescriptionWithScore()));
	}
}
