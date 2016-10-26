/*
MIT License

Copyright (c) 2016 Sebastian Janisch

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
package org.sjanisch.skillview.contribution.impl.git;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.sjanisch.skillview.contribution.api.Contribution;
import org.sjanisch.skillview.contribution.api.ContributionId;
import org.sjanisch.skillview.contribution.api.ContributionRetrievalException;
import org.sjanisch.skillview.contribution.api.ContributionService;
import org.sjanisch.skillview.contribution.api.Contributor;
import org.sjanisch.skillview.contribution.api.Project;
import org.sjanisch.skillview.contribution.impl.DefaultContribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the {@link ContributionService} for GIT repositories.
 * 
 * @author sebastianjanisch
 *
 */
public class GitContributionService implements ContributionService {

	private static final Logger log = LoggerFactory.getLogger(GitContributionService.class);

	private static final String EMPTY = "0000000000000000000000000000000000000000";

	private Supplier<Repository> repositorySupplier;
	private Project project;

	/**
	 * 
	 * @param repositorySupplier
	 *            supplier for fresh repository instances. Must not be
	 *            {@code null}.
	 * @param project
	 *            the project that this repository relates to. Must not be
	 *            {@code null}.
	 */
	public GitContributionService(Supplier<Repository> repositorySupplier, Project project) {
		this.repositorySupplier = Objects.requireNonNull(repositorySupplier, "repositorySupplier");
		this.project = Objects.requireNonNull(project, "project");
	}

	@Override
	public Stream<Contribution> retrieveContributions(Instant startExclusive, Instant endInclusive) {
		Objects.requireNonNull(startExclusive, "startExclusive");
		Objects.requireNonNull(endInclusive, "endInclusive");

		Repository repository = repositorySupplier.get();
		Runnable closeRepository = () -> repository.close();
		try (Git git = new Git(repository)) {
			try {
				Helper helper = new Helper(repository, project, git, startExclusive, endInclusive);
				Stream<Contribution> contributions = helper.readContributions().onClose(closeRepository);
				return contributions;
			} catch (Exception e) {
				String msg = "Could not retrieve contributions between %s and %s";
				msg = String.format(msg, startExclusive, endInclusive);
				throw new ContributionRetrievalException(msg, e);
			}
		}
	}

	private static class Helper {

		private final Repository repository;
		private final Project project;
		private final Git git;
		private final Instant startExclusive;
		private final Instant endInclusive;

		public Helper(Repository repository, Project project, Git git, Instant startExclusive, Instant endInclusive) {
			this.repository = repository;
			this.project = project;
			this.git = git;
			this.startExclusive = startExclusive;
			this.endInclusive = endInclusive;
		}

		private Stream<Contribution> readContributions() throws Exception {
			info(() -> String.format("Reading contributions for %s", repository.toString()));

			List<Ref> branches = git.branchList().call();

			// @formatter:off
			Stream<Contribution> contributions = branches.stream()
				.map(branch -> readContributionsFromBranch(branch))
				.flatMap(Function.identity());
			// @formatter:on

			return contributions;
		}

		private Stream<Contribution> readContributionsFromBranch(Ref branch) {
			info(() -> String.format("Entering branch %s", branch.getName()));

			try {
				Iterable<RevCommit> commits = git.log().add(repository.resolve(branch.getName())).call();

				List<RevCommit> commitsList = StreamSupport.stream(commits.spliterator(), false).filter(commit -> {
					Instant commitTime = Instant.ofEpochSecond(commit.getCommitTime());
					if (commitTime.isAfter(startExclusive) && !commitTime.isAfter(endInclusive)) {
						return true;
					}
					return false;

				}).collect(Collectors.toList());

				info(() -> String.format("Found %s commits in branch %s", commitsList.size(), branch.toString()));

				Stream<Contribution> result = IntStream.range(0, commitsList.size() - 1).mapToObj(i -> {
					RevCommit oldCommit = commitsList.get(i + 1);
					RevCommit newCommit = commitsList.get(i);

					AbstractTreeIterator oldTreeParser = prepareTreeParser(repository, oldCommit.getName());
					AbstractTreeIterator newTreeParser = prepareTreeParser(repository, newCommit.getName());

					Stream<Contribution> contributions = readContributionsFromCommit(newCommit, newTreeParser,
							oldTreeParser);

					return contributions;
				}).flatMap(Function.identity());

				return result;
			} catch (Exception e) {
				String msg = "Could not retrieve contributions for branch " + branch.getName();
				throw new ContributionRetrievalException(msg, e);
			}

		}

		private Stream<Contribution> readContributionsFromCommit(RevCommit commit, AbstractTreeIterator newTreeParser,
				AbstractTreeIterator oldTreeParser) {
			try {
				debug(() -> String.format("Reading contribution from commit %s", commit.name()));

				List<DiffEntry> diff = git.diff().setOldTree(oldTreeParser).setNewTree(newTreeParser).call();

				debug(() -> String.format("Found %s diff entries for commit %s", diff.size(), commit.name()));

				Stream<Contribution> contributions = diff.stream().map(diffEntry -> toContribution(diffEntry, commit));

				return contributions;
			} catch (GitAPIException e) {
				String msg = "Could not retrieve contributions for commit " + commit.toString();
				throw new ContributionRetrievalException(msg, e);
			}
		}

		private Contribution toContribution(DiffEntry entry, RevCommit commit) {
			AbbreviatedObjectId oldId = entry.getOldId();
			AbbreviatedObjectId newId = entry.getNewId();

			try {
				ByteBuffer newContent = ByteBuffer.wrap("".getBytes());
				ByteBuffer oldContent = null;

				if (!newId.name().equals(EMPTY)) {
					ObjectLoader newLoader = repository.open(newId.toObjectId());
					newContent = ByteBuffer.wrap(newLoader.getBytes());
				}
				if (!oldId.name().equals(EMPTY)) {
					ObjectLoader oldLoader = repository.open(oldId.toObjectId());
					oldContent = ByteBuffer.wrap(oldLoader.getBytes());
				}

				ContributionId id = ContributionId.of(newId.name());
				Contributor contributor = Contributor.of(commit.getCommitterIdent().getName());
				Instant commitTime = Instant.ofEpochSecond(commit.getCommitTime());
				String message = commit.getFullMessage();
				String path = entry.getNewPath();

				trace(() -> String.format("Reading contribution from user %s at %s of %s", contributor.getName(),
						commitTime, path));

				return new DefaultContribution(id, project, contributor, commitTime, message, path, newContent,
						oldContent);
			} catch (Exception e) {
				throw new ContributionRetrievalException("Error reading diff entry", e);
			}

		}

		private static AbstractTreeIterator prepareTreeParser(Repository repository, String objectId) {
			try (RevWalk walk = new RevWalk(repository)) {
				try {
					RevCommit commit = walk.parseCommit(ObjectId.fromString(objectId));
					RevTree tree = walk.parseTree(commit.getTree().getId());

					CanonicalTreeParser treeParser = new CanonicalTreeParser();
					try (ObjectReader reader = repository.newObjectReader()) {
						treeParser.reset(reader, tree.getId());
					}

					walk.dispose();

					return treeParser;
				} catch (Exception e) {
					String msg = "Could not prepare tree parser for object id " + objectId.toString();
					throw new ContributionRetrievalException(msg, e);
				}
			}
		}

	}

	private static void info(Supplier<String> info) {
		if (log.isInfoEnabled()) {
			log.info(info.get());
		}
	}

	private static void debug(Supplier<String> debug) {
		if (log.isDebugEnabled()) {
			log.debug(debug.get());
		}
	}

	private static void trace(Supplier<String> trace) {
		if (log.isTraceEnabled()) {
			log.trace(trace.get());
		}
	}

}
