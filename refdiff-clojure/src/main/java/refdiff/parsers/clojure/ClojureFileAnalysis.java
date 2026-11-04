package refdiff.parsers.clojure;

import java.util.List;

import refdiff.core.cst.CstNode;

/**
 * Declaration and call-site information extracted from one Clojure file.
 */
final class ClojureFileAnalysis {

	private final List<CstNode> declarations;
	private final List<ClojureCallSite> calls;

	ClojureFileAnalysis(List<CstNode> declarations, List<ClojureCallSite> calls) {
		this.declarations = declarations;
		this.calls = calls;
	}

	List<CstNode> getDeclarations() {
		return declarations;
	}

	List<ClojureCallSite> getCalls() {
		return calls;
	}
}
