package refdiff.cli;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import refdiff.core.RefDiff;
import refdiff.core.diff.CstDiff;
import refdiff.core.diff.Relationship;
import refdiff.parsers.clojure.ClojurePlugin;

public class RunClojureCommit {

	public static void main(String[] args) {
		if (args.length < 2 || args[0].isEmpty() || args[1].isEmpty()) {
			System.err.println("Usage: RunClojureCommit <repository-url-or-path> <commit-sha> [cache-folder]");
			System.exit(2);
		}

		File cacheFolder = args.length >= 3 && !args[2].isEmpty()
			? new File(args[2])
			: new File(System.getProperty("java.io.tmpdir"), "refdiff-repos");

		try {
			run(args[0], args[1], cacheFolder);
		} catch (Exception e) {
			System.err.println("Unable to compute diff: " + e.getMessage());
			if (e.getCause() != null && e.getCause().getMessage() != null) {
				System.err.println("Cause: " + e.getCause().getMessage());
			}
			System.exit(1);
		}
	}

	private static void run(String repositoryUrl, String commitSha, File cacheFolder) throws Exception {
		cacheFolder.mkdirs();
		File repositoryFolder = new File(cacheFolder, repositoryFolderName(repositoryUrl));

		RefDiff refDiff = new RefDiff(new ClojurePlugin());
		File repository = refDiff.cloneGitRepository(repositoryFolder, repositoryUrl);

		CstDiff diff = refDiff.computeDiffForCommit(repository, commitSha);

		List<Relationship> refactorings = diff.getRefactoringRelationships().stream()
			.sorted(Comparator.comparing(Relationship::getStandardDescription))
			.collect(Collectors.toList());

		System.out.println();
		System.out.printf("Repository: %s%n", repositoryUrl);
		System.out.printf("Commit: %s%n", commitSha);
		System.out.printf("Refactorings found: %d%n", refactorings.size());
		for (Relationship relationship : refactorings) {
			System.out.println(relationship.getStandardDescription());
		}
	}

	private static String repositoryFolderName(String repositoryUrl) {
		String cleanUrl = repositoryUrl;
		while (cleanUrl.endsWith("/")) {
			cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
		}
		if (cleanUrl.endsWith(".git")) {
			cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 4);
		}

		String[] parts = cleanUrl.split("/");
		String name = parts[parts.length - 1];
		String owner = parts.length >= 2 ? parts[parts.length - 2] : "";
		String folderName = owner.isEmpty() ? name : owner + "-" + name;
		folderName = folderName.replaceAll("[^A-Za-z0-9._-]", "_");
		if (folderName.isEmpty()) {
			folderName = "repository";
		}
		return folderName + ".git";
	}
}
