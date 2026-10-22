package refdiff.parsers.clojure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import refdiff.core.cst.CstNode;
import refdiff.core.cst.CstNodeRelationship;
import refdiff.core.cst.CstNodeRelationshipType;
import refdiff.core.cst.CstRoot;

/**
 * Resolves syntactic calls to declared Clojure functions and adds USE
 * relationships to the CST.
 *
 * <p>Resolution is intentionally conservative: unqualified calls prefer the
 * caller's namespace, qualified calls use the declared namespace or a
 * {@code :as} alias, and a global fallback is used only when exactly one
 * declaration matches the symbol and arity.
 */
final class ClojureUseResolver {

	private ClojureUseResolver() {
	}

	static void addUseRelationships(CstRoot root, List<ClojureCallSite> calls) {
		List<CstNode> functions = new ArrayList<>();
		root.forEachNode((node, depth) -> {
			if (ClojureDeclarationExtractor.FUNCTION_TYPE.equals(node.getType())) {
				functions.add(node);
			}
		});

		Map<String, List<CstNode>> functionsByName = new HashMap<>();
		for (CstNode function : functions) {
			functionsByName.computeIfAbsent(function.getSimpleName(), name -> new ArrayList<>()).add(function);
		}

		for (ClojureCallSite call : calls) {
			CstNode callee = resolve(call, functionsByName);
			if (callee != null) {
				root.getRelationships().add(new CstNodeRelationship(
					CstNodeRelationshipType.USE, call.getCaller().getId(), callee.getId()));
			}
		}
	}

	private static CstNode resolve(ClojureCallSite call, Map<String, List<CstNode>> functionsByName) {
		String symbol = call.getSymbol();
		int argumentCount = call.getArgumentCount();
		int slash = symbol.lastIndexOf('/');

		if (slash > 0 && slash < symbol.length() - 1) {
			String prefix = symbol.substring(0, slash);
			String name = symbol.substring(slash + 1);
			String targetNamespace = call.getNamespaceAliases().getOrDefault(prefix, prefix);
			return uniqueArityMatch(functionsByName.get(name), targetNamespace, argumentCount);
		}

		String callerNamespace = namespaceOf(call.getCaller());
		List<CstNode> sameNamespace = functionsInNamespace(functionsByName.get(symbol), callerNamespace);
		if (!sameNamespace.isEmpty()) {
			return uniqueArityMatch(sameNamespace, argumentCount);
		}

		return uniqueArityMatch(functionsByName.get(symbol), argumentCount);
	}

	private static List<CstNode> functionsInNamespace(List<CstNode> candidates, String namespace) {
		List<CstNode> result = new ArrayList<>();
		if (candidates != null) {
			for (CstNode candidate : candidates) {
				if (namespace.equals(namespaceOf(candidate))) {
					result.add(candidate);
				}
			}
		}
		return result;
	}

	private static CstNode uniqueArityMatch(List<CstNode> candidates, String namespace, int argumentCount) {
		List<CstNode> matching = new ArrayList<>();
		if (candidates != null) {
			for (CstNode candidate : candidates) {
				if (namespace.equals(namespaceOf(candidate)) && matchesArity(candidate, argumentCount)) {
					matching.add(candidate);
				}
			}
		}
		return unique(matching);
	}

	private static CstNode uniqueArityMatch(List<CstNode> candidates, int argumentCount) {
		List<CstNode> matching = new ArrayList<>();
		if (candidates != null) {
			for (CstNode candidate : candidates) {
				if (matchesArity(candidate, argumentCount)) {
					matching.add(candidate);
				}
			}
		}
		return unique(matching);
	}

	private static CstNode unique(List<CstNode> candidates) {
		return candidates.size() == 1 ? candidates.get(0) : null;
	}

	private static boolean matchesArity(CstNode function, int argumentCount) {
		String localName = function.getLocalName();
		if (localName == null) {
			return false;
		}

		int slash = localName.lastIndexOf('/');
		if (slash < 0 || slash == localName.length() - 1) {
			return false;
		}

		String arity = localName.substring(slash + 1);
		boolean variadic = arity.endsWith("+");
		if (variadic) {
			arity = arity.substring(0, arity.length() - 1);
		}

		try {
			int fixedArity = Integer.parseInt(arity);
			return variadic ? argumentCount >= fixedArity : argumentCount == fixedArity;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static String namespaceOf(CstNode node) {
		String namespace = node.getNamespace();
		if (namespace == null) {
			return "";
		}
		return namespace.endsWith(".") ? namespace.substring(0, namespace.length() - 1) : namespace;
	}
}
