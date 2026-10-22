package refdiff.parsers.clojure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import refdiff.core.cst.TokenPosition;

public class RewriteCljTokenizerTest {

	private final RewriteCljTokenizer tokenizer = new RewriteCljTokenizer();

	@Test
	public void shouldTokenizeClojureForms() {
		ClojureSourceText text = new ClojureSourceText("(ns demo)\n\n; comment\n(def x 1)\n");
		List<TokenPosition> tokens = tokenizer.tokenize(text);

		assertEquals(Arrays.asList("(", "ns", "demo", ")", ";", "comment", "(", "def", "x", "1", ")"),
			tokenTexts(text, tokens));
		assertValid(text, tokens);
	}

	@Test
	public void shouldTokenizeEmptySource() {
		ClojureSourceText text = new ClojureSourceText("");
		List<TokenPosition> tokens = tokenizer.tokenize(text);

		assertTrue(tokens.isEmpty());
	}

	@Test
	public void shouldMapTokenOffsetsForCrLf() {
		ClojureSourceText text = new ClojureSourceText("(def a 1)\r\n(def b 2)\r\n");
		List<TokenPosition> tokens = tokenizer.tokenize(text);

		assertEquals(Arrays.asList("(", "def", "a", "1", ")", "(", "def", "b", "2", ")"),
			tokenTexts(text, tokens));
		assertEquals(11, tokens.get(5).getStart());
		assertEquals("(", text.getOriginal().substring(tokens.get(5).getStart(), tokens.get(5).getEnd()));
		assertValid(text, tokens);
	}

	@Test
	public void shouldMapTokenOffsetsForCrFf() {
		ClojureSourceText text = new ClojureSourceText("(a)\r\f(b)");
		List<TokenPosition> tokens = tokenizer.tokenize(text);

		assertEquals(Arrays.asList("(", "a", ")", "(", "b", ")"), tokenTexts(text, tokens));
		assertEquals(5, tokens.get(3).getStart());
		assertValid(text, tokens);
	}

	@Test
	public void shouldMapTokenOffsetsForLoneCr() {
		ClojureSourceText text = new ClojureSourceText("(a)\r(b)");
		List<TokenPosition> tokens = tokenizer.tokenize(text);

		assertEquals(Arrays.asList("(", "a", ")", "(", "b", ")"), tokenTexts(text, tokens));
		assertEquals(4, tokens.get(3).getStart());
		assertValid(text, tokens);
	}

	@Test
	public void shouldKeepTokensSortedAndNonOverlapping() {
		ClojureSourceText text = new ClojureSourceText("; first\r\n(def x [1 2])\r\n#_(ignored)\n");
		List<TokenPosition> tokens = tokenizer.tokenize(text);

		assertTrue(tokens.size() > 0);
		assertValid(text, tokens);
	}

	private static List<String> tokenTexts(ClojureSourceText text, List<TokenPosition> tokens) {
		List<String> result = new ArrayList<>();
		for (TokenPosition token : tokens) {
			result.add(text.getOriginal().substring(token.getStart(), token.getEnd()));
		}
		return result;
	}

	private static void assertValid(ClojureSourceText text, List<TokenPosition> tokens) {
		TokenPosition previous = null;
		for (TokenPosition token : tokens) {
			assertTrue(token.getStart() >= 0);
			assertTrue(token.getEnd() <= text.getOriginalLength());
			assertTrue(token.getStart() < token.getEnd());
			if (previous != null) {
				assertTrue(previous.getEnd() <= token.getStart());
			}
			previous = token;
		}
	}
}
