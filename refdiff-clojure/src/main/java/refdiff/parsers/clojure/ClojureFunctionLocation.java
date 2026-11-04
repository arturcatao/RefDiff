package refdiff.parsers.clojure;

import refdiff.core.cst.Location;

/** Adds the arity clause span in original UTF-16 offsets, with an exclusive end. */
public final class ClojureFunctionLocation extends Location {
	private final int arityBegin;
	private final int arityEnd;

	ClojureFunctionLocation(Location declaration, int arityBegin, int arityEnd) {
		super(declaration.getFile(), declaration.getBegin(), declaration.getEnd(), declaration.getLine(),
			declaration.getBodyBegin(), declaration.getBodyEnd());
		this.arityBegin = arityBegin;
		this.arityEnd = arityEnd;
	}

	public int getArityBegin() {
		return arityBegin;
	}

	public int getArityEnd() {
		return arityEnd;
	}
}
