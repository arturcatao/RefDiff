package refdiff.parsers.clojure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.RT;
import refdiff.core.cst.CstNode;
import refdiff.core.cst.Location;
import refdiff.core.cst.Parameter;
import refdiff.core.cst.Stereotype;

/**
 * Extracts {@code ns}, {@code defn}, and {@code defn-} declarations from the
 * concrete syntax tree produced by rewrite-clj.
 *
 * <p>A multi-arity {@code defn} is represented by one node per arity. This
 * keeps each body and parameter list unambiguous, which is required by
 * RefDiff's source-representation builder.
 */
final class ClojureDeclarationExtractor {

	static final String FUNCTION_TYPE = "FunctionDeclaration";

	private static final IFn PARSE_STRING_ALL;
	private static final IFn TAG;
	private static final IFn NODE_TYPE;
	private static final IFn INNER;
	private static final IFn CHILDREN;
	private static final IFn META;
	private static final IFn STRING;

	private static final Keyword ROW = Keyword.intern(null, "row");
	private static final Keyword COL = Keyword.intern(null, "col");
	private static final Keyword END_ROW = Keyword.intern(null, "end-row");
	private static final Keyword END_COL = Keyword.intern(null, "end-col");

	private static final Set<String> IGNORED_TAGS;
	private static final Set<String> METADATA_TAGS;
	private static final Set<String> DEFINITION_HEADS;
	private static final Set<String> LET_FORMS;
	private static final Set<String> FN_FORMS;
	private static final Set<String> SPECIAL_FORMS;
	private static final Set<String> IGNORED_CODE_FORMS;

	static {
		IFn require = Clojure.var("clojure.core", "require");
		require.invoke(Clojure.read("rewrite-clj.parser"));
		require.invoke(Clojure.read("rewrite-clj.node"));
		require.invoke(Clojure.read("rewrite-clj.node.protocols"));

		PARSE_STRING_ALL = Clojure.var("rewrite-clj.parser", "parse-string-all");
		TAG = Clojure.var("rewrite-clj.node", "tag");
		NODE_TYPE = Clojure.var("rewrite-clj.node.protocols", "node-type");
		INNER = Clojure.var("rewrite-clj.node", "inner?");
		CHILDREN = Clojure.var("rewrite-clj.node", "children");
		META = Clojure.var("clojure.core", "meta");
		STRING = Clojure.var("rewrite-clj.node", "string");

		Set<String> ignoredTags = new HashSet<>();
		Collections.addAll(ignoredTags, "whitespace", "newline", "comma", "comment", "uneval");
		IGNORED_TAGS = Collections.unmodifiableSet(ignoredTags);

		Set<String> metadataTags = new HashSet<>();
		Collections.addAll(metadataTags, "meta", "meta*");
		METADATA_TAGS = Collections.unmodifiableSet(metadataTags);

		Set<String> definitionHeads = new HashSet<>();
		Collections.addAll(definitionHeads, "defn", "defn-");
		DEFINITION_HEADS = Collections.unmodifiableSet(definitionHeads);

		Set<String> letForms = new HashSet<>();
		Collections.addAll(letForms, "let", "let*", "loop", "loop*");
		LET_FORMS = Collections.unmodifiableSet(letForms);

		Set<String> fnForms = new HashSet<>();
		Collections.addAll(fnForms, "fn", "fn*");
		FN_FORMS = Collections.unmodifiableSet(fnForms);

		Set<String> specialForms = new HashSet<>();
		Collections.addAll(specialForms, "def", "if", "do", "var", "recur", "throw", "try",
			"catch", "finally", "monitor-enter", "monitor-exit", "new", "set!", ".",
			"deftype*", "reify*", "case*", "import*", "letfn*", "ns", "defn", "defn-");
		SPECIAL_FORMS = Collections.unmodifiableSet(specialForms);

		Set<String> ignoredCodeForms = new HashSet<>();
		Collections.addAll(ignoredCodeForms, "quote", "syntax-quote");
		IGNORED_CODE_FORMS = Collections.unmodifiableSet(ignoredCodeForms);
	}

	ClojureFileAnalysis analyze(ClojureSourceText sourceText, String filePath, AtomicInteger idGenerator) {
		Object root;
		try {
			root = PARSE_STRING_ALL.invoke(sourceText.getOriginal());
		} catch (RuntimeException e) {
			throw new RuntimeException("Could not parse Clojure source", e);
		}

		List<Object> forms = semanticChildren(root);
		NamespaceInfo namespaceInfo = findNamespace(forms);
		String clojureNamespace = namespaceInfo.name;
		String functionNamespace = clojureNamespace == null || clojureNamespace.isEmpty()
			? ""
			: clojureNamespace + ".";

		List<CstNode> functions = new ArrayList<>();
		List<ClojureCallSite> calls = new ArrayList<>();
		for (Object form : forms) {
			Object declaration = unwrapMetadata(form);
			List<Object> elements = semanticChildren(declaration);
			if (elements.isEmpty()) {
				continue;
			}

			String head = tokenString(elements.get(0));
			if (DEFINITION_HEADS.contains(head)) {
				extractFunctions(form, declaration, elements, functionNamespace, namespaceInfo.aliases,
					sourceText, filePath, idGenerator, functions, calls);
			}
		}
		return new ClojureFileAnalysis(functions, calls);
	}

	private static NamespaceInfo findNamespace(List<Object> forms) {
		for (Object form : forms) {
			Object declaration = unwrapMetadata(form);
			List<Object> elements = semanticChildren(declaration);
			if (elements.size() < 2) {
				continue;
			}
			if ("ns".equals(tokenString(elements.get(0)))) {
				Object nameNode = unwrapMetadata(elements.get(1));
				String name = tokenString(nameNode);
				Map<String, String> aliases = new HashMap<>();
				collectNamespaceAliases(elements, aliases);
				return new NamespaceInfo(name, Collections.unmodifiableMap(aliases));
			}
		}
		return new NamespaceInfo(null, Collections.emptyMap());
	}

	private static void collectNamespaceAliases(List<Object> nsElements, Map<String, String> aliases) {
		for (int i = 1; i < nsElements.size(); i++) {
			Object element = unwrapMetadata(nsElements.get(i));
			if (!"list".equals(tag(element))) {
				continue;
			}
			List<Object> requireElements = semanticChildren(element);
			if (requireElements.isEmpty()) {
				continue;
			}
			String head = keywordText(requireElements.get(0));
			if (!":require".equals(head) && !":require-macros".equals(head)) {
				continue;
			}
			for (int j = 1; j < requireElements.size(); j++) {
				collectAliasFromRequire(requireElements.get(j), aliases);
			}
		}
	}

	private static void collectAliasFromRequire(Object spec, Map<String, String> aliases) {
		Object vector = unwrapMetadata(spec);
		if (!"vector".equals(tag(vector))) {
			return;
		}
		List<Object> parts = semanticChildren(vector);
		if (parts.isEmpty()) {
			return;
		}
		String namespace = tokenString(unwrapMetadata(parts.get(0)));
		if (namespace == null) {
			return;
		}
		for (int i = 1; i + 1 < parts.size(); i++) {
			String keyword = keywordText(parts.get(i));
			if (":as".equals(keyword) || ":as-alias".equals(keyword)) {
				String alias = tokenString(unwrapMetadata(parts.get(i + 1)));
				if (alias != null && !alias.isEmpty()) {
					aliases.put(alias, namespace);
				}
			}
		}
	}

	private static void extractFunctions(Object originalForm, Object declaration, List<Object> elements,
			String namespace, Map<String, String> namespaceAliases, ClojureSourceText sourceText,
			String filePath, AtomicInteger idGenerator, List<CstNode> functions, List<ClojureCallSite> calls) {
		if (elements.size() < 2) {
			return;
		}

		Object nameNode = unwrapMetadata(elements.get(1));
		String simpleName = tokenString(nameNode);
		if (simpleName == null || simpleName.isEmpty()) {
			return;
		}

		int next = 2;
		Object lastHeaderNode = elements.get(1);
		if (next < elements.size() && isStringNode(elements.get(next))) {
			lastHeaderNode = elements.get(next);
			next++;
		}
		if (next < elements.size() && "map".equals(tag(elements.get(next)))) {
			lastHeaderNode = elements.get(next);
			next++;
		}

		List<Object> tail = elements.subList(next, elements.size());
		if (tail.isEmpty()) {
			int bodyPosition = end(lastHeaderNode, sourceText);
			addFunctionNode(originalForm, simpleName, 0, false, Collections.emptyList(),
				bodyPosition, bodyPosition, namespace, sourceText, filePath, idGenerator, functions);
			return;
		}

		Object first = unwrapMetadata(tail.get(0));
		if ("vector".equals(tag(first))) {
			addFunctionNode(originalForm, simpleName, first, tail.subList(1, tail.size()),
				namespace, namespaceAliases, sourceText, filePath, idGenerator, functions, calls);
			return;
		}

		if ("list".equals(tag(first)) && allArities(tail)) {
			for (Object tailNode : tail) {
				Object arityForm = unwrapMetadata(tailNode);
				List<Object> arityElements = semanticChildren(arityForm);
				Object parameters = unwrapMetadata(arityElements.get(0));
				addFunctionNode(originalForm, simpleName, parameters,
					arityElements.subList(1, arityElements.size()), namespace, namespaceAliases,
					sourceText, filePath, idGenerator, functions, calls);
			}
		}
	}

	private static boolean allArities(List<Object> nodes) {
		for (Object node : nodes) {
			Object arityForm = unwrapMetadata(node);
			List<Object> elements = semanticChildren(arityForm);
			if (!"list".equals(tag(arityForm)) || elements.isEmpty()) {
				return false;
			}
			if (!"vector".equals(tag(unwrapMetadata(elements.get(0))))) {
				return false;
			}
		}
		return true;
	}

	private static void addFunctionNode(Object originalForm, String simpleName, Object parameters,
			List<Object> bodyNodes, String namespace, Map<String, String> namespaceAliases,
			ClojureSourceText sourceText, String filePath, AtomicInteger idGenerator,
			List<CstNode> functions, List<ClojureCallSite> calls) {
		ParsedParameters parsedParameters = parseParameters(parameters, sourceText);
		int arity = parsedParameters.fixedArity;
		String localName = simpleName + "/" + arity + (parsedParameters.variadic ? "+" : "");

		int bodyBegin = bodyNodes.isEmpty()
			? end(parameters, sourceText)
			: start(bodyNodes.get(0), sourceText);
		int bodyEnd = bodyNodes.isEmpty()
			? end(parameters, sourceText)
			: end(bodyNodes.get(bodyNodes.size() - 1), sourceText);

		CstNode function = addNode(originalForm, simpleName, localName, parsedParameters.parameters,
			bodyBegin, bodyEnd, namespace, sourceText, filePath, idGenerator, functions);
		collectCalls(bodyNodes, function, parsedParameters.localNames, namespaceAliases, calls);
	}

	private static void addFunctionNode(Object originalForm, String simpleName, int arity, boolean variadic,
			List<Parameter> parameters, int bodyBegin, int bodyEnd, String namespace,
			ClojureSourceText sourceText, String filePath, AtomicInteger idGenerator, List<CstNode> functions) {
		String localName = simpleName + "/" + arity + (variadic ? "+" : "");
		addNode(originalForm, simpleName, localName, parameters, bodyBegin, bodyEnd, namespace, sourceText,
			filePath, idGenerator, functions);
	}

	private static CstNode addNode(Object originalForm, String simpleName, String localName,
			List<Parameter> parameters, int normalizedBodyBegin, int normalizedBodyEnd, String namespace,
			ClojureSourceText sourceText, String filePath, AtomicInteger idGenerator, List<CstNode> functions) {
		int begin = sourceText.toOriginalOffset(start(originalForm, sourceText));
		int end = sourceText.toOriginalOffset(end(originalForm, sourceText));
		int bodyBegin = sourceText.toOriginalOffset(normalizedBodyBegin);
		int bodyEnd = sourceText.toOriginalOffset(normalizedBodyEnd);

		CstNode node = new CstNode(idGenerator.incrementAndGet());
		node.setType(FUNCTION_TYPE);
		node.setSimpleName(simpleName);
		node.setLocalName(localName);
		node.setNamespace(namespace);
		node.setParameters(parameters);
		node.setLocation(new Location(filePath, begin, end, sourceText.lineNumber(begin), bodyBegin, bodyEnd));
		node.addStereotypes(Stereotype.HAS_BODY);
		functions.add(node);
		return node;
	}

	private static ParsedParameters parseParameters(Object parameters, ClojureSourceText sourceText) {
		List<Parameter> parsed = new ArrayList<>();
		Set<String> localNames = new HashSet<>();
		int fixedArity = 0;
		boolean variadic = false;

		for (Object parameterNode : semanticChildren(parameters)) {
			Object parameter = unwrapMetadata(parameterNode);
			String token = tokenString(parameter);
			if ("&".equals(token)) {
				variadic = true;
				continue;
			}

			parsed.add(new Parameter(parameterName(parameter, sourceText)));
			collectBindingNames(parameter, localNames);
			if (!variadic) {
				fixedArity++;
			}
		}
		return new ParsedParameters(parsed, localNames, fixedArity, variadic);
	}

	private static String parameterName(Object parameter, ClojureSourceText sourceText) {
		String token = tokenString(parameter);
		if (token != null) {
			return token;
		}
		return sourceText.originalSlice(start(parameter, sourceText), end(parameter, sourceText));
	}

	private static void collectCalls(List<Object> bodyNodes, CstNode caller, Set<String> localNames,
			Map<String, String> namespaceAliases, List<ClojureCallSite> calls) {
		for (Object bodyNode : bodyNodes) {
			visitForCalls(bodyNode, localNames, caller, namespaceAliases, calls);
		}
	}

	private static void visitForCalls(Object node, Set<String> localNames, CstNode caller,
			Map<String, String> namespaceAliases, List<ClojureCallSite> calls) {
		Object value = unwrapMetadata(node);
		if ("list".equals(tag(value))) {
			visitListForCalls(value, localNames, caller, namespaceAliases, calls);
			return;
		}
		for (Object child : semanticChildren(value)) {
			visitForCalls(child, localNames, caller, namespaceAliases, calls);
		}
	}

	private static void visitListForCalls(Object list, Set<String> localNames, CstNode caller,
			Map<String, String> namespaceAliases, List<ClojureCallSite> calls) {
		List<Object> elements = semanticChildren(list);
		if (elements.isEmpty()) {
			return;
		}

		Object headNode = unwrapMetadata(elements.get(0));
		String head = tokenString(headNode);
		if (head == null) {
			visitAllForCalls(elements, 0, localNames, caller, namespaceAliases, calls);
			return;
		}

		if (IGNORED_CODE_FORMS.contains(head)) {
			return;
		}
		if (LET_FORMS.contains(head)) {
			visitLetForCalls(elements, localNames, caller, namespaceAliases, calls);
			return;
		}
		if (FN_FORMS.contains(head)) {
			visitFnForCalls(elements, localNames, caller, namespaceAliases, calls);
			return;
		}

		if (!SPECIAL_FORMS.contains(head) && !localNames.contains(head) && !isStringNode(headNode)) {
			calls.add(new ClojureCallSite(caller, head, elements.size() - 1, namespaceAliases));
		}
		visitAllForCalls(elements, 1, localNames, caller, namespaceAliases, calls);
	}

	private static void visitLetForCalls(List<Object> elements, Set<String> localNames, CstNode caller,
			Map<String, String> namespaceAliases, List<ClojureCallSite> calls) {
		if (elements.size() < 2) {
			return;
		}

		Object bindingVector = unwrapMetadata(elements.get(1));
		if (!"vector".equals(tag(bindingVector))) {
			visitAllForCalls(elements, 1, localNames, caller, namespaceAliases, calls);
			return;
		}

		List<Object> bindings = semanticChildren(bindingVector);
		Set<String> scope = new HashSet<>(localNames);
		for (int i = 0; i < bindings.size(); i += 2) {
			Object bindingForm = unwrapMetadata(bindings.get(i));
			if (i + 1 < bindings.size()) {
				visitForCalls(bindings.get(i + 1), scope, caller, namespaceAliases, calls);
			}
			collectBindingNames(bindingForm, scope);
		}
		visitAllForCalls(elements, 2, scope, caller, namespaceAliases, calls);
	}

	private static void visitFnForCalls(List<Object> elements, Set<String> localNames, CstNode caller,
			Map<String, String> namespaceAliases, List<ClojureCallSite> calls) {
		int parameterIndex = -1;
		for (int i = 1; i < elements.size(); i++) {
			if ("vector".equals(tag(unwrapMetadata(elements.get(i))))) {
				parameterIndex = i;
				break;
			}
		}

		if (parameterIndex == -1) {
			visitAllForCalls(elements, 1, localNames, caller, namespaceAliases, calls);
			return;
		}

		Set<String> scope = new HashSet<>(localNames);
		if (parameterIndex == 2) {
			String functionName = tokenString(unwrapMetadata(elements.get(1)));
			if (functionName != null && !functionName.isEmpty()) {
				scope.add(functionName);
			}
		}
		collectBindingNames(unwrapMetadata(elements.get(parameterIndex)), scope);
		visitAllForCalls(elements, parameterIndex + 1, scope, caller, namespaceAliases, calls);
	}

	private static void visitAllForCalls(List<Object> elements, int start, Set<String> localNames,
			CstNode caller, Map<String, String> namespaceAliases, List<ClojureCallSite> calls) {
		for (int i = start; i < elements.size(); i++) {
			visitForCalls(elements.get(i), localNames, caller, namespaceAliases, calls);
		}
	}

	private static void collectBindingNames(Object node, Set<String> names) {
		Object value = unwrapMetadata(node);
		if ("token".equals(tag(value))) {
			if (!isStringNode(value)) {
				String name = string(value);
				if (!"&".equals(name) && !name.isEmpty()) {
					names.add(name);
				}
			}
			return;
		}
		for (Object child : semanticChildren(value)) {
			collectBindingNames(child, names);
		}
	}

	private static String keywordText(Object node) {
		if (!"keyword".equals(nodeType(node))) {
			return null;
		}
		return string(node);
	}

	private static String tag(Object node) {
		return keywordName(TAG.invoke(node));
	}

	private static String nodeType(Object node) {
		return keywordName(NODE_TYPE.invoke(node));
	}

	private static String keywordName(Object value) {
		if (value instanceof Keyword) {
			return ((Keyword) value).getName();
		}
		return String.valueOf(value).replaceFirst("^:", "");
	}

	private static boolean isInner(Object node) {
		return Boolean.TRUE.equals(INNER.invoke(node));
	}

	private static List<Object> semanticChildren(Object node) {
		if (!isInner(node)) {
			return Collections.emptyList();
		}

		ISeq seq = RT.seq(CHILDREN.invoke(node));
		if (seq == null) {
			return Collections.emptyList();
		}

		List<Object> children = new ArrayList<>();
		for (ISeq current = seq; current != null; current = current.next()) {
			Object child = current.first();
			if (!IGNORED_TAGS.contains(tag(child))) {
				children.add(child);
			}
		}
		return children;
	}

	private static Object unwrapMetadata(Object node) {
		while (METADATA_TAGS.contains(tag(node))) {
			List<Object> children = semanticChildren(node);
			if (children.size() < 2) {
				break;
			}
			node = children.get(children.size() - 1);
		}
		return node;
	}

	private static String tokenString(Object node) {
		if (!"token".equals(tag(node))) {
			return null;
		}
		return string(node);
	}

	private static boolean isStringNode(Object node) {
		String tag = tag(node);
		if ("multi-line".equals(tag)) {
			return true;
		}
		if (!"token".equals(tag)) {
			return false;
		}
		String text = string(node);
		return text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"';
	}

	private static String string(Object node) {
		return String.valueOf(STRING.invoke(node));
	}

	private static int start(Object node, ClojureSourceText sourceText) {
		IPersistentMap meta = (IPersistentMap) META.invoke(node);
		return sourceText.normalizedOffset(number(meta, ROW), number(meta, COL));
	}

	private static int end(Object node, ClojureSourceText sourceText) {
		IPersistentMap meta = (IPersistentMap) META.invoke(node);
		return sourceText.normalizedOffset(number(meta, END_ROW), number(meta, END_COL));
	}

	private static int number(IPersistentMap meta, Keyword key) {
		if (meta == null) {
			throw new IllegalStateException("rewrite-clj node is missing position metadata");
		}
		Object value = meta.valAt(key);
		if (!(value instanceof Number)) {
			throw new IllegalStateException("rewrite-clj node is missing metadata " + key);
		}
		return ((Number) value).intValue();
	}

	private static final class ParsedParameters {
		private final List<Parameter> parameters;
		private final Set<String> localNames;
		private final int fixedArity;
		private final boolean variadic;

		private ParsedParameters(List<Parameter> parameters, Set<String> localNames, int fixedArity,
				boolean variadic) {
			this.parameters = parameters;
			this.localNames = localNames;
			this.fixedArity = fixedArity;
			this.variadic = variadic;
		}
	}

	private static final class NamespaceInfo {
		private final String name;
		private final Map<String, String> aliases;

		private NamespaceInfo(String name, Map<String, String> aliases) {
			this.name = name;
			this.aliases = aliases;
		}
	}
}
