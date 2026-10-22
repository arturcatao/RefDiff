package refdiff.parsers.clojure;

/**
 * Keeps the source text exactly as read from {@code SourceFileSet} while also
 * providing the newline-normalized text used by rewrite-clj.
 *
 * rewrite-clj normalizes CRLF, CRFF, and lone CR to LF. The returned node
 * positions therefore refer to the normalized text. This class maps those
 * positions back to Java String offsets in the original source.
 */
public final class ClojureSourceText {

	private final String original;
	private final String normalized;
	private final int[] originalOffsets;
	private final int[] lineStarts;

	public ClojureSourceText(String original) {
		if (original == null) {
			throw new IllegalArgumentException("original source must not be null");
		}
		this.original = original;

		StringBuilder normalizedBuilder = new StringBuilder(original.length());
		int[] offsets = new int[original.length() + 1];
		int normalizedIndex = 0;
		int originalIndex = 0;

		while (originalIndex < original.length()) {
			char c = original.charAt(originalIndex);
			offsets[normalizedIndex] = originalIndex;

			if (c == '\r') {
				normalizedBuilder.append('\n');
				if (originalIndex + 1 < original.length()) {
					char next = original.charAt(originalIndex + 1);
					if (next == '\n' || next == '\f') {
						originalIndex += 2;
					} else {
						originalIndex += 1;
					}
				} else {
					originalIndex += 1;
				}
			} else {
				normalizedBuilder.append(c);
				originalIndex += 1;
			}
			normalizedIndex++;
		}

		offsets[normalizedIndex] = original.length();
		this.normalized = normalizedBuilder.toString();
		this.originalOffsets = offsets;
		this.lineStarts = computeLineStarts(this.normalized);
	}

	public String getOriginal() {
		return original;
	}

	public String getNormalized() {
		return normalized;
	}

	public int getOriginalLength() {
		return original.length();
	}

	public int getNormalizedLength() {
		return normalized.length();
	}

	public int normalizedOffset(int row, int column) {
		if (row < 1 || row > lineStarts.length) {
			throw new IllegalArgumentException("row out of range: " + row);
		}
		if (column < 1) {
			throw new IllegalArgumentException("column out of range: " + column);
		}

		int offset = lineStarts[row - 1] + column - 1;
		if (offset > normalized.length()) {
			throw new IllegalArgumentException("column out of range: " + column + " for row " + row);
		}
		return offset;
	}

	public int toOriginalOffset(int normalizedOffset) {
		if (normalizedOffset < 0 || normalizedOffset > normalized.length()) {
			throw new IllegalArgumentException("normalized offset out of range: " + normalizedOffset);
		}
		return originalOffsets[normalizedOffset];
	}

	public int toOriginalOffset(int row, int column) {
		return toOriginalOffset(normalizedOffset(row, column));
	}

	public int lineNumber(int originalOffset) {
		if (originalOffset < 0 || originalOffset > original.length()) {
			throw new IllegalArgumentException("original offset out of range: " + originalOffset);
		}

		int line = 1;
		for (int i = 0; i < originalOffset; i++) {
			char c = original.charAt(i);
			if (c == '\r') {
				line++;
				if (i + 1 < originalOffset && (original.charAt(i + 1) == '\n' || original.charAt(i + 1) == '\f')) {
					i++;
				}
			} else if (c == '\n') {
				line++;
			}
		}
		return line;
	}

	public String originalSlice(int normalizedStart, int normalizedEnd) {
		if (normalizedEnd < normalizedStart) {
			throw new IllegalArgumentException("normalized end is before start");
		}
		int originalStart = toOriginalOffset(normalizedStart);
		int originalEnd = toOriginalOffset(normalizedEnd);
		return original.substring(originalStart, originalEnd);
	}

	private static int[] computeLineStarts(String text) {
		int lineCount = 1;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				lineCount++;
			}
		}

		int[] starts = new int[lineCount];
		int line = 1;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				starts[line++] = i + 1;
			}
		}
		return starts;
	}
}
