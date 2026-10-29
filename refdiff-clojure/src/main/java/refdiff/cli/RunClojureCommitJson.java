package refdiff.cli;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import refdiff.core.RefDiff;
import refdiff.core.cst.CstNode;
import refdiff.core.cst.CstRoot;
import refdiff.core.diff.CstDiff;
import refdiff.core.diff.Relationship;
import refdiff.core.io.GitHelper;
import refdiff.parsers.clojure.ClojurePlugin;

/** Runs RefDiff for a Clojure commit and writes one JSON document to stdout. */
public class RunClojureCommitJson {

	private static final ObjectMapper JSON = new ObjectMapper()
		.enable(SerializationFeature.INDENT_OUTPUT);

	public static void main(String[] args) {
		if (args.length < 2 || args[0].isEmpty() || args[1].isEmpty()) {
			writeError("invalid_arguments", "Usage: RunClojureCommitJson <repository-url-or-path> <commit-sha> [cache-folder]");
			System.exit(2);
		}

		File cacheFolder = args.length >= 3 && !args[2].isEmpty()
			? new File(args[2])
			: new File(System.getProperty("java.io.tmpdir"), "refdiff-repos");

		try {
			ObjectNode result = run(args[0], args[1], cacheFolder);
			writeJson(result, System.out);
		} catch (RunnerException e) {
			writeError(e.status, e.getMessage());
			System.exit(1);
		} catch (Exception e) {
			writeError("refdiff_failed", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
			System.exit(1);
		}
	}

	private static ObjectNode run(String repositoryUrl, String commitSha, File cacheFolder) throws Exception {
		if (!cacheFolder.exists() && !cacheFolder.mkdirs()) {
			throw new IllegalStateException("Unable to create cache folder: " + cacheFolder.getAbsolutePath());
		}
		File repositoryFolder = new File(cacheFolder, repositoryFolderName(repositoryUrl));
		cloneIfMissing(repositoryFolder, repositoryUrl);

		String parentSha;
		String analyzedCommitSha;
		try (Repository repository = GitHelper.openRepository(repositoryFolder);
				RevWalk revWalk = new RevWalk(repository)) {
			org.eclipse.jgit.lib.ObjectId commitId = repository.resolve(commitSha);
			if (commitId == null) {
				throw new RunnerException("missing_commit", "Commit not found: " + commitSha);
			}
			RevCommit commit = revWalk.parseCommit(commitId);
			if (commit.getParentCount() == 0) {
				throw new RunnerException("root_commit", "Commit has no parent: " + commitSha);
			}
			if (commit.getParentCount() > 1) {
				throw new RunnerException("merge_commit", "Commit has multiple parents: " + commitSha);
			}
			analyzedCommitSha = commit.getId().name();
			parentSha = commit.getParent(0).getId().name();
		}

		RefDiff refDiff = new RefDiff(new ClojurePlugin());
		CstDiff diff = refDiff.computeDiffForCommit(repositoryFolder, commitSha);
		List<Relationship> refactorings = diff.getRefactoringRelationships().stream()
			.sorted(Comparator.comparing(Relationship::getStandardDescription))
			.collect(Collectors.toList());

		ObjectNode result = JSON.createObjectNode();
		result.put("schema_version", "1.0");
		result.put("status", "completed");
		result.put("repository", repositoryUrl);
		ObjectNode commits = result.putObject("commits");
		commits.put("before", parentSha);
		commits.put("after", analyzedCommitSha);

		ObjectNode entities = result.putObject("entities");
		entities.set("before", entities(diff.getBefore()));
		entities.set("after", entities(diff.getAfter()));

		ArrayNode refactoringNodes = result.putArray("refactorings");
		for (Relationship relationship : refactorings) {
			ObjectNode item = refactoringNodes.addObject();
			item.put("type", relationship.getType().name());
			if (relationship.getSimilarity() != null) {
				item.put("similarity", relationship.getSimilarity());
			} else {
				item.putNull("similarity");
			}
			item.set("before", entity(relationship.getNodeBefore()));
			item.set("after", entity(relationship.getNodeAfter()));
		}
		result.put("refactoring_count", refactorings.size());
		return result;
	}

	private static void cloneIfMissing(File repositoryFolder, String repositoryUrl) throws Exception {
		if (repositoryFolder.exists()) {
			return;
		}
		try (Git ignored = Git.cloneRepository()
				.setURI(repositoryUrl)
				.setDirectory(repositoryFolder)
				.setBare(true)
				.setCloneAllBranches(true)
				.call()) {
			// Keep stdout reserved for the JSON document.
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
		return (folderName.isEmpty() ? "repository" : folderName) + ".git";
	}

	private static ArrayNode entities(CstRoot root) {
		List<CstNode> nodes = new ArrayList<>();
		root.forEachNode((node, depth) -> nodes.add(node));
		nodes.sort(Comparator
			.comparing((CstNode node) -> node.getLocation().getFile())
			.thenComparingInt(node -> node.getLocation().getBegin())
			.thenComparing(CstNode::getLocalName));
		ArrayNode result = JSON.createArrayNode();
		for (CstNode node : nodes) {
			result.add(entity(node));
		}
		return result;
	}

	private static ObjectNode entity(CstNode node) {
		if (node == null) {
			return null;
		}
		ObjectNode result = JSON.createObjectNode();
		result.put("id", node.getId());
		result.put("type", node.getType());
		result.put("name", node.getSimpleName());
		result.put("signature", node.getLocalName());
		if (node.getNamespace() == null) {
			result.putNull("namespace");
		} else {
			result.put("namespace", node.getNamespace());
		}
		ObjectNode location = result.putObject("location");
		location.put("file", node.getLocation().getFile());
		location.put("begin", node.getLocation().getBegin());
		location.put("end", node.getLocation().getEnd());
		location.put("line", node.getLocation().getLine());
		location.put("body_begin", node.getLocation().getBodyBegin());
		location.put("body_end", node.getLocation().getBodyEnd());
		ArrayNode parameters = result.putArray("parameters");
		node.getParameters().forEach(parameter -> parameters.add(parameter.getName()));
		return result;
	}

	private static void writeError(String code, String message) {
		ObjectNode error = JSON.createObjectNode();
		error.put("schema_version", "1.0");
		error.put("status", code);
		error.put("error", message);
		writeJson(error, System.out);
	}

	private static void writeJson(ObjectNode json, PrintStream output) {
		try {
			JSON.writeValue(output, json);
		} catch (Exception e) {
			System.err.println("Unable to write JSON output: " + e.getMessage());
		}
	}

	private static class RunnerException extends Exception {
		private static final long serialVersionUID = 1L;
		private final String status;

		private RunnerException(String status, String message) {
			super(message);
			this.status = status;
		}
	}
}
