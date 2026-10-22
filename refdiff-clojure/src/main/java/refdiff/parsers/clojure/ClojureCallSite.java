package refdiff.parsers.clojure;

import java.util.Map;

import refdiff.core.cst.CstNode;

/**
 * A syntactic function call found in the body of a Clojure function.
 */
final class ClojureCallSite {

	private final CstNode caller;
	private final String symbol;
	private final int argumentCount;
	private final Map<String, String> namespaceAliases;

	ClojureCallSite(CstNode caller, String symbol, int argumentCount, Map<String, String> namespaceAliases) {
		this.caller = caller;
		this.symbol = symbol;
		this.argumentCount = argumentCount;
		this.namespaceAliases = namespaceAliases;
	}

	CstNode getCaller() {
		return caller;
	}

	String getSymbol() {
		return symbol;
	}

	int getArgumentCount() {
		return argumentCount;
	}

	Map<String, String> getNamespaceAliases() {
		return namespaceAliases;
	}
}
