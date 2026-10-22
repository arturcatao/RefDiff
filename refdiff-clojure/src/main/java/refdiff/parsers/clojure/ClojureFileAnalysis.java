package refdiff.parsers.clojure;

import java.util.List;

import refdiff.core.cst.CstNode;

/**
 * Declaration and call-site information extracted from one Clojure file.
 */
final class ClojureFileAnalysis {

	private final List<CstNode> functions;
	private final List<ClojureCallSite> calls;

	ClojureFileAnalysis(List<CstNode> functions, List<ClojureCallSite> calls) {
		this.functions = functions;
		this.calls = calls;
	}

	List<CstNode> getFunctions() {
		return functions;
	}

	List<ClojureCallSite> getCalls() {
		return calls;
	}
}
