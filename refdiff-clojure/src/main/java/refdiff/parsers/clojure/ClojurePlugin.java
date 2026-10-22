package refdiff.parsers.clojure;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import refdiff.core.cst.CstNode;
import refdiff.core.cst.CstRoot;
import refdiff.core.cst.Location;
import refdiff.core.cst.Stereotype;
import refdiff.core.cst.TokenPosition;
import refdiff.core.cst.TokenizedSource;
import refdiff.core.io.FilePathFilter;
import refdiff.core.io.SourceFile;
import refdiff.core.io.SourceFileSet;
import refdiff.parsers.LanguagePlugin;

/**
 * Clojure language plugin. It creates one CST node per source file and one
 * {@code FunctionDeclaration} node per {@code defn}/{@code defn-} arity.
 */
public class ClojurePlugin implements LanguagePlugin {

	public static final String FILE_TYPE = "File";

	private final RewriteCljTokenizer tokenizer = new RewriteCljTokenizer();
	private final ClojureDeclarationExtractor declarationExtractor = new ClojureDeclarationExtractor();

	@Override
	public CstRoot parse(SourceFileSet sources) throws Exception {
		CstRoot root = new CstRoot();
		AtomicInteger nextId = new AtomicInteger(0);

		for (SourceFile sourceFile : sources.getSourceFiles()) {
			String sourceCode = sources.readContent(sourceFile);
			String filePath = sourceFile.getPath();
			ClojureSourceText sourceText = new ClojureSourceText(sourceCode);
			CstNode fileNode = new CstNode(nextId.incrementAndGet());

			List<TokenPosition> tokens;
			List<CstNode> functions;
			try {
				tokens = tokenizer.tokenize(sourceText);
				functions = declarationExtractor.extract(sourceText, filePath, nextId);
			} catch (RuntimeException e) {
				throw new RuntimeException("Error parsing " + sources.describeLocation(sourceFile) + ": " + e.getMessage(), e);
			}

			fileNode.setType(FILE_TYPE);
			fileNode.setLocalName(fileName(filePath));
			fileNode.setSimpleName(fileName(filePath));
			fileNode.setNamespace(namespace(filePath));
			fileNode.setLocation(new Location(filePath, 0, sourceCode.length(), 1, 0, sourceCode.length()));
			fileNode.addStereotypes(Stereotype.HAS_BODY);
			for (CstNode function : functions) {
				fileNode.addNode(function);
			}
			root.addNode(fileNode);
			root.addTokenizedFile(new TokenizedSource(filePath, tokens));
		}

		return root;
	}

	private static String fileName(String filePath) {
		int lastSeparator = filePath.lastIndexOf('/');
		return lastSeparator == -1 ? filePath : filePath.substring(lastSeparator + 1);
	}

	private static String namespace(String filePath) {
		int lastSeparator = filePath.lastIndexOf('/');
		return lastSeparator == -1 ? "" : filePath.substring(0, lastSeparator + 1);
	}

	@Override
	public FilePathFilter getAllowedFilesFilter() {
		return new FilePathFilter(Arrays.asList(".clj", ".cljs", ".cljc"));
	}
}
