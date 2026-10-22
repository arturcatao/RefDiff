package refdiff.parsers.clojure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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

	static {
		IFn require = Clojure.var("clojure.core", "require");
		require.invoke(Clojure.read("rewrite-clj.parser"));
		require.invoke(Clojure.read("rewrite-clj.node"));

		PARSE_STRING_ALL = Clojure.var("rewrite-clj.parser", "parse-string-all");
		TAG = Clojure.var("rewrite-clj.node", "tag");
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
	}

	List<CstNode> extract(ClojureSourceText sourceText, String filePath, AtomicInteger idGenerator) {
		Object root;
		try {
			root = PARSE_STRING_ALL.invoke(sourceText.getOriginal());
		} catch (RuntimeException e) {
			throw new RuntimeException("Could not parse Clojure source", e);
		}

		List<Object> forms = semanticChildren(root);
		String clojureNamespace = findNamespace(forms);
		String functionNamespace = clojureNamespace == null || clojureNamespace.isEmpty()
			? ""
			: clojureNamespace + ".";

		List<CstNode> functions = new ArrayList<>();
		for (Object form : forms) {
			Object declaration = unwrapMetadata(form);
			List<Object> elements = semanticChildren(declaration);
			if (elements.isEmpty()) {
				continue;
			}

			String head = tokenString(elements.get(0));
			if (DEFINITION_HEADS.contains(head)) {
				extractFunctions(form, declaration, elements, functionNamespace, sourceText, filePath, idGenerator, functions);
			}
		}
		return functions;
	}

	private static String findNamespace(List<Object> forms) {
		for (Object form : forms) {
			Object declaration = unwrapMetadata(form);
			List<Object> elements = semanticChildren(declaration);
			if (elements.size() < 2) {
				continue;
			}
			if ("ns".equals(tokenString(elements.get(0)))) {
				Object nameNode = unwrapMetadata(elements.get(1));
				return tokenString(nameNode);
			}
		}
		return null;
	}

	private static void extractFunctions(Object originalForm, Object declaration, List<Object> elements,
			String namespace, ClojureSourceText sourceText, String filePath, AtomicInteger idGenerator,
			List<CstNode> functions) {
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
				namespace, sourceText, filePath, idGenerator, functions);
			return;
		}

		if ("list".equals(tag(first)) && allArities(tail)) {
			for (Object tailNode : tail) {
				Object arityForm = unwrapMetadata(tailNode);
				List<Object> arityElements = semanticChildren(arityForm);
				Object parameters = unwrapMetadata(arityElements.get(0));
				addFunctionNode(originalForm, simpleName, parameters,
					arityElements.subList(1, arityElements.size()), namespace, sourceText, filePath,
					idGenerator, functions);
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
			List<Object> bodyNodes, String namespace, ClojureSourceText sourceText, String filePath,
			AtomicInteger idGenerator, List<CstNode> functions) {
		ParsedParameters parsedParameters = parseParameters(parameters, sourceText);
		int arity = parsedParameters.fixedArity;
		String localName = simpleName + "/" + arity + (parsedParameters.variadic ? "+" : "");

		int bodyBegin = bodyNodes.isEmpty()
			? end(parameters, sourceText)
			: start(bodyNodes.get(0), sourceText);
		int bodyEnd = bodyNodes.isEmpty()
			? end(parameters, sourceText)
			: end(bodyNodes.get(bodyNodes.size() - 1), sourceText);

		addNode(originalForm, simpleName, localName, parsedParameters.parameters, bodyBegin, bodyEnd,
			namespace, sourceText, filePath, idGenerator, functions);
	}

	private static void addFunctionNode(Object originalForm, String simpleName, int arity, boolean variadic,
			List<Parameter> parameters, int bodyBegin, int bodyEnd, String namespace,
			ClojureSourceText sourceText, String filePath, AtomicInteger idGenerator, List<CstNode> functions) {
		String localName = simpleName + "/" + arity + (variadic ? "+" : "");
		addNode(originalForm, simpleName, localName, parameters, bodyBegin, bodyEnd, namespace, sourceText,
			filePath, idGenerator, functions);
	}

	private static void addNode(Object originalForm, String simpleName, String localName,
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
	}

	private static ParsedParameters parseParameters(Object parameters, ClojureSourceText sourceText) {
		List<Parameter> parsed = new ArrayList<>();
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
			if (!variadic) {
				fixedArity++;
			}
		}
		return new ParsedParameters(parsed, fixedArity, variadic);
	}

	private static String parameterName(Object parameter, ClojureSourceText sourceText) {
		String token = tokenString(parameter);
		if (token != null) {
			return token;
		}
		return sourceText.originalSlice(start(parameter, sourceText), end(parameter, sourceText));
	}

	private static String tag(Object node) {
		Object tag = TAG.invoke(node);
		if (tag instanceof Keyword) {
			return ((Keyword) tag).getName();
		}
		return String.valueOf(tag).replaceFirst("^:", "");
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
		private final int fixedArity;
		private final boolean variadic;

		private ParsedParameters(List<Parameter> parameters, int fixedArity, boolean variadic) {
			this.parameters = parameters;
			this.fixedArity = fixedArity;
			this.variadic = variadic;
		}
	}
}
