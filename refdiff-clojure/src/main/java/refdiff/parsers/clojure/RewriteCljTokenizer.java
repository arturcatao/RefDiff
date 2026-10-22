package refdiff.parsers.clojure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.RT;
import refdiff.core.cst.TokenPosition;

/**
 * Uses rewrite-clj only to parse the source and expose its syntax tree. The
 * tree is then traversed to produce lexical tokens. No Clojure form is
 * evaluated or converted with {@code sexpr}.
 */
public class RewriteCljTokenizer {

	private static final IFn PARSE_STRING_ALL;
	private static final IFn TAG;
	private static final IFn INNER;
	private static final IFn CHILDREN;
	private static final IFn LEADER_LENGTH;
	private static final IFn META;

	private static final Keyword ROW = Keyword.intern(null, "row");
	private static final Keyword COL = Keyword.intern(null, "col");
	private static final Keyword END_ROW = Keyword.intern(null, "end-row");
	private static final Keyword END_COL = Keyword.intern(null, "end-col");

	private static final Set<String> IGNORED_TAGS;
	private static final Pattern NON_WHITESPACE = Pattern.compile("\\S+");

	static {
		IFn require = Clojure.var("clojure.core", "require");
		require.invoke(Clojure.read("rewrite-clj.parser"));
		require.invoke(Clojure.read("rewrite-clj.node"));

		PARSE_STRING_ALL = Clojure.var("rewrite-clj.parser", "parse-string-all");
		TAG = Clojure.var("rewrite-clj.node", "tag");
		INNER = Clojure.var("rewrite-clj.node", "inner?");
		CHILDREN = Clojure.var("rewrite-clj.node", "children");
		LEADER_LENGTH = Clojure.var("rewrite-clj.node", "leader-length");
		META = Clojure.var("clojure.core", "meta");

		Set<String> ignoredTags = new HashSet<>();
		Collections.addAll(ignoredTags, "whitespace", "newline", "comma");
		IGNORED_TAGS = Collections.unmodifiableSet(ignoredTags);
	}

	public List<TokenPosition> tokenize(ClojureSourceText sourceText) {
		Object root;
		try {
			root = PARSE_STRING_ALL.invoke(sourceText.getOriginal());
		} catch (RuntimeException e) {
			throw new RuntimeException("Could not parse Clojure source", e);
		}

		List<TokenPosition> tokens = new ArrayList<>();
		visit(root, sourceText, tokens);
		tokens.sort(Comparator.comparingInt(TokenPosition::getStart)
			.thenComparingInt(TokenPosition::getEnd));
		validate(tokens, sourceText.getOriginalLength());
		return tokens;
	}

	private void visit(Object node, ClojureSourceText sourceText, List<TokenPosition> tokens) {
		String tag = tag(node);

		if (IGNORED_TAGS.contains(tag)) {
			return;
		}

		if ("forms".equals(tag)) {
			for (Object child : children(node)) {
				visit(child, sourceText, tokens);
			}
			return;
		}

		if (!isInner(node)) {
			if ("comment".equals(tag)) {
				addCommentTokens(node, sourceText, tokens);
			} else {
				addNodeToken(node, sourceText, tokens);
			}
			return;
		}

		List<Object> childNodes = children(node);
		if (childNodes.isEmpty()) {
			addEmptyInnerNodeTokens(node, sourceText, tokens);
			return;
		}

		int nodeStart = start(node, sourceText);
		int nodeEnd = end(node, sourceText);
		int firstChildStart = start(childNodes.get(0), sourceText);
		int lastChildEnd = end(childNodes.get(childNodes.size() - 1), sourceText);

		if (firstChildStart > nodeStart) {
			addNonWhitespaceTokens(nodeStart, firstChildStart, sourceText, tokens);
		}
		for (Object child : childNodes) {
			visit(child, sourceText, tokens);
		}
		if (lastChildEnd < nodeEnd) {
			addNonWhitespaceTokens(lastChildEnd, nodeEnd, sourceText, tokens);
		}
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

	private static List<Object> children(Object node) {
		ISeq seq = RT.seq(CHILDREN.invoke(node));
		if (seq == null) {
			return Collections.emptyList();
		}

		List<Object> children = new ArrayList<>();
		for (ISeq current = seq; current != null; current = current.next()) {
			children.add(current.first());
		}
		return children;
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

	private static void addNodeToken(Object node, ClojureSourceText sourceText, List<TokenPosition> tokens) {
		int start = start(node, sourceText);
		int end = end(node, sourceText);
		addTokenIfValid(start, end, sourceText, tokens);
	}

	private static void addCommentTokens(Object node, ClojureSourceText sourceText, List<TokenPosition> tokens) {
		int start = start(node, sourceText);
		int end = end(node, sourceText);
		addNonWhitespaceTokens(start, end, sourceText, tokens);
	}

	private static void addEmptyInnerNodeTokens(Object node, ClojureSourceText sourceText, List<TokenPosition> tokens) {
		int start = start(node, sourceText);
		int end = end(node, sourceText);
		int leaderLength = ((Number) LEADER_LENGTH.invoke(node)).intValue();
		int childrenStart = Math.min(start + leaderLength, end);

		addNonWhitespaceTokens(start, childrenStart, sourceText, tokens);
		addNonWhitespaceTokens(childrenStart, end, sourceText, tokens);
	}

	private static void addNonWhitespaceTokens(int normalizedStart, int normalizedEnd,
			ClojureSourceText sourceText, List<TokenPosition> tokens) {
		if (normalizedEnd <= normalizedStart) {
			return;
		}

		int originalStart = sourceText.toOriginalOffset(normalizedStart);
		int originalEnd = sourceText.toOriginalOffset(normalizedEnd);
		String text = sourceText.getOriginal().substring(originalStart, originalEnd);
		Matcher matcher = NON_WHITESPACE.matcher(text);
		while (matcher.find()) {
			tokens.add(new TokenPosition(originalStart + matcher.start(), originalStart + matcher.end()));
		}
	}

	private static void addTokenIfValid(int normalizedStart, int normalizedEnd,
			ClojureSourceText sourceText, List<TokenPosition> tokens) {
		if (normalizedEnd <= normalizedStart) {
			return;
		}
		int originalStart = sourceText.toOriginalOffset(normalizedStart);
		int originalEnd = sourceText.toOriginalOffset(normalizedEnd);
		if (originalStart < originalEnd) {
			tokens.add(new TokenPosition(originalStart, originalEnd));
		}
	}

	private static void validate(List<TokenPosition> tokens, int sourceLength) {
		TokenPosition previous = null;
		for (TokenPosition token : tokens) {
			if (token.getStart() < 0 || token.getEnd() > sourceLength) {
				throw new IllegalStateException("token range is outside the source: ["
					+ token.getStart() + ", " + token.getEnd() + ")");
			}
			if (token.getStart() >= token.getEnd()) {
				throw new IllegalStateException("token range is empty or inverted: ["
					+ token.getStart() + ", " + token.getEnd() + ")");
			}
			if (previous != null && previous.getEnd() > token.getStart()) {
				throw new IllegalStateException("tokens overlap: ["
					+ previous.getStart() + ", " + previous.getEnd() + ") and ["
					+ token.getStart() + ", " + token.getEnd() + ")");
			}
			previous = token;
		}
	}
}
