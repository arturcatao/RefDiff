package refdiff.parsers.clojure;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClojureSourceTextTest {

	@Test
	public void shouldKeepOffsetMappingForLineFeed() {
		ClojureSourceText text = new ClojureSourceText("a\nb");

		assertEquals("a\nb", text.getNormalized());
		assertMapping(text, 0, 0);
		assertMapping(text, 1, 1);
		assertMapping(text, 2, 2);
		assertMapping(text, 3, 3);
	}

	@Test
	public void shouldMapCrLfToOriginalOffsets() {
		ClojureSourceText text = new ClojureSourceText("a\r\nb");

		assertEquals("a\nb", text.getNormalized());
		assertEquals(4, text.getOriginalLength());
		assertEquals(3, text.getNormalizedLength());
		assertMapping(text, 0, 0);
		assertMapping(text, 1, 1);
		assertMapping(text, 2, 3);
		assertMapping(text, 3, 4);
		assertEquals(3, text.toOriginalOffset(2, 1));
		assertEquals(4, text.toOriginalOffset(2, 2));
	}

	@Test
	public void shouldMapCrFfToOriginalOffsets() {
		ClojureSourceText text = new ClojureSourceText("a\r\fb");

		assertEquals("a\nb", text.getNormalized());
		assertEquals(4, text.getOriginalLength());
		assertEquals(3, text.getNormalizedLength());
		assertMapping(text, 0, 0);
		assertMapping(text, 1, 1);
		assertMapping(text, 2, 3);
		assertMapping(text, 3, 4);
	}

	@Test
	public void shouldMapLoneCrToOriginalOffsets() {
		ClojureSourceText text = new ClojureSourceText("a\rb");

		assertEquals("a\nb", text.getNormalized());
		assertEquals(3, text.getOriginalLength());
		assertEquals(3, text.getNormalizedLength());
		assertMapping(text, 0, 0);
		assertMapping(text, 1, 1);
		assertMapping(text, 2, 2);
		assertMapping(text, 3, 3);
	}

	@Test
	public void shouldMapMixedLineEndings() {
		ClojureSourceText text = new ClojureSourceText("a\r\nb\rc\r\fd");

		assertEquals("a\nb\nc\nd", text.getNormalized());
		assertEquals(9, text.getOriginalLength());
		assertEquals(7, text.getNormalizedLength());
		assertMapping(text, 0, 0);
		assertMapping(text, 1, 1);
		assertMapping(text, 2, 3);
		assertMapping(text, 3, 4);
		assertMapping(text, 4, 5);
		assertMapping(text, 5, 6);
		assertMapping(text, 6, 8);
		assertMapping(text, 7, 9);
		assertEquals(8, text.toOriginalOffset(4, 1));
	}

	@Test
	public void shouldSupportTrailingNewlineLineStarts() {
		ClojureSourceText text = new ClojureSourceText("a\n");

		assertEquals(2, text.normalizedOffset(2, 1));
		assertEquals(2, text.toOriginalOffset(2, 1));
	}

	@Test
	public void shouldComputeLineNumbersForAllNewlineForms() {
		assertEquals(2, new ClojureSourceText("a\nb").lineNumber(2));
		assertEquals(2, new ClojureSourceText("a\r\nb").lineNumber(3));
		assertEquals(2, new ClojureSourceText("a\rb").lineNumber(2));
		assertEquals(2, new ClojureSourceText("a\r\fb").lineNumber(3));
		assertEquals(3, new ClojureSourceText("a\r\nb\rc").lineNumber(5));
	}

	private static void assertMapping(ClojureSourceText text, int normalizedOffset, int originalOffset) {
		assertEquals(originalOffset, text.toOriginalOffset(normalizedOffset));
	}
}
