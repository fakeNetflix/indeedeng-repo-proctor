package com.indeed.proctor.store;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.apache.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.Maps.newHashMapWithExpectedSize;

public class GitProctor extends FileBasedProctorStore {
    private static final Logger LOGGER = Logger.getLogger(GitProctor.class);

    /* Storage Schema:
        ${gitPath}/
            ${testDefinitionsDirectory}/
                test-name-one/
                    definition.json
                    metadata.json
                test-name-two/
                    definition.json
                    metadata.json
    */

    private final Git git;
    @Nullable
    private final String branchName;

    public GitProctor(final String gitPath,
                      final String username,
                      final String password,
                      final String testDefinitionsDirectory) {
        this(gitPath, username, password, testDefinitionsDirectory, null);
    }

    public GitProctor(final String gitPath,
                      final String username,
                      final String password,
                      final String testDefinitionsDirectory,
                      @Nullable final String branchName) {
        this(new GitProctorCore(gitPath, username, password, testDefinitionsDirectory, Files.createTempDir()), testDefinitionsDirectory, branchName);
    }

    public GitProctor(final String gitPath,
                      final String username,
                      final String password) {
        this(gitPath, username, password, DEFAULT_TEST_DEFINITIONS_DIRECTORY);
    }

    public GitProctor(final GitProctorCore core) {
        this(core, DEFAULT_TEST_DEFINITIONS_DIRECTORY);
    }

    public GitProctor(final GitProctorCore core, final String testDefinitionsDirectory) {
        this(core, testDefinitionsDirectory, null);
    }

    /**
     *
     * @param core a core with a defined remote and defined local working directory
     * @param testDefinitionsDirectory where test definitions are located inside the local git repository
     * @param branchName stay on this branch if not null, else default branch from remote
     */
    public GitProctor(final GitProctorCore core, final String testDefinitionsDirectory, @Nullable final String branchName) {
        super(core, testDefinitionsDirectory);
        git = core.getGit();
        this.branchName = branchName;
        if (branchName != null) {
            checkoutBranch(branchName);
        }
    }

    @Override
    public void verifySetup() throws StoreException {
        final String refName = getGitCore().getRefName();
        try {
            final ObjectId branchHead = git.getRepository().resolve(refName);
            if (branchHead == null) {
                throw new StoreException("git repository couldn't resolve " + refName);
            }
        } catch (final IOException e) {
            throw new StoreException("Could get resolve " + refName);
        }
    }

    protected GitProctorCore getGitCore() {
        return (GitProctorCore) core;
    }

    @Override
    public boolean cleanUserWorkspace(final String username) {
        getGitCore().undoLocalChanges();
        getGitCore().initializeRepository(false);
        if (this.branchName != null) {
            checkoutBranch(this.branchName);
        }
        return true;
    }

    @Override
    public String getLatestVersion() throws StoreException {
        try {
            final Ref branch = git.getRepository().findRef(getGitCore().getRefName());
            return branch.getObjectId().name();
        } catch (final IOException e) {
            throw new StoreException(e);
        }
    }

    @Override
    public List<Revision> getMatrixHistory(final int start,
                                           final int limit) throws StoreException {
        final LogCommand logCommand;
        try {
            final ObjectId branchHead = git.getRepository().resolve(getGitCore().getRefName());
            logCommand = git.log()
                    .add(branchHead)
                    .setSkip(start)
                    .setMaxCount(limit);
            return getHistoryFromLogCommand(logCommand);
        } catch (final IOException e) {
            throw new StoreException("Could not get history for starting at " + getGitCore().getRefName(), e);
        }
    }

    @Override
    public List<Revision> getHistory(final String test,
                                     final int start,
                                     final int limit) throws StoreException {
        return getHistory(test, getLatestVersion(), start, limit);
    }

    @Override
    public List<Revision> getHistory(final String test,
                                     final String revision,
                                     final int start,
                                     final int limit) throws StoreException {
        try {
            final ObjectId commitId = ObjectId.fromString(revision);
            final LogCommand logCommand = git.log()
                    // TODO: create path to definition.json file, sanitize test name for invalid / relative characters
                    .addPath(getTestDefinitionsDirectory() + File.separator + test + File.separator + FileBasedProctorStore.TEST_DEFINITION_FILENAME)
                    .add(commitId)
                    .setSkip(start)
                    .setMaxCount(limit);
            return getHistoryFromLogCommand(logCommand);

        } catch (final IOException e) {
            throw new StoreException("Could not get history for " + test + " starting at " + getGitCore().getRefName(), e);
        }
    }

    @Override
    public Map<String, List<Revision>> getAllHistories() throws StoreException {
        final Repository repository = git.getRepository();
        try {
            final ObjectId head = repository.resolve(Constants.HEAD);
            final RevWalk revWalk = new RevWalk(repository);
            final DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
            df.setRepository(git.getRepository());
            df.setDiffComparator(RawTextComparator.DEFAULT);

            final HistoryParser historyParser = new HistoryParser(revWalk, df, getTestDefinitionsDirectory());
            return historyParser.parseFromHead(head);

        } catch (final IOException e) {
            throw new StoreException("Could not get history " + getGitCore().getRefName(), e);
        }
    }

    private List<Revision> getHistoryFromLogCommand(final LogCommand command) throws StoreException {
        final List<Revision> versions = Lists.newArrayList();
        final Iterable<RevCommit> commits;
        try {
            commits = command.call();
        } catch (final GitAPIException e) {
            throw new StoreException("Could not get history", e);
        }
        for (final RevCommit commit : commits) {
            versions.add(new Revision(
                    commit.getName(),
                    commit.getAuthorIdent().toExternalString(),
                    new Date(Long.valueOf(commit.getCommitTime()) * 1000 /* convert seconds to milliseconds */),
                    commit.getFullMessage()
            ));
        }
        return versions;
    }

    public void checkoutBranch(final String branchName) {
        getGitCore().checkoutBranch(branchName);
    }

    @Override
    public void refresh() throws StoreException {
        getGitCore().refresh();
    }

    @Override
    public String getName() {
        return "GitProctor-" + branchName;
    }

    public static class HistoryParser {
        private static final int EXPECTED_NUMBER_ACTIVE_TESTS = 7000;
        final RevWalk revWalk;
        final DiffFormatter diffFormatter;
        final Pattern testNamePattern;
        @Nullable
        final Set<String> activeTests;

        private static final Cache<String, List<DiffEntry>> DIFF_ENTRIES_CACHE = CacheBuilder
                .newBuilder()
                .expireAfterAccess(1, TimeUnit.DAYS)
                .maximumSize(1_000_000)
                .build();

        public HistoryParser(final RevWalk revWalk,
                             final DiffFormatter diffFormatter,
                             final String definitionDirectory,
                             @Nullable final Set<String> activeTests) {
            this.revWalk = revWalk;
            this.diffFormatter = diffFormatter;
            testNamePattern = compileTestNamePattern(definitionDirectory);
            this.activeTests = activeTests;
        }

        public HistoryParser(final RevWalk revWalk,
                             final DiffFormatter diffFormatter,
                             final String definitionDirectory) {
            this(revWalk, diffFormatter, definitionDirectory, null);
        }

        /**
         * @return a map of testnames and git commits making changes to given tests
         */
        public Map<String, List<Revision>> parseFromHead(final ObjectId head) throws IOException {
            final Map<String, List<Revision>> histories = newHashMapWithExpectedSize(EXPECTED_NUMBER_ACTIVE_TESTS);
            final Set<ObjectId> visited = Sets.newHashSet();
            final Queue<RevCommit> queue = new LinkedList<>();
            queue.add(revWalk.parseCommit(head));
            final long start = System.currentTimeMillis();
            while (!queue.isEmpty()) {
                parseCommit(queue.poll(), histories, visited, queue);
            }
            final long middle = System.currentTimeMillis();
            sortByDate(histories);
            final long end = System.currentTimeMillis();
            LOGGER.info(String.format("Took %d ms to parse, %d ms to sort revisions in chronological order", middle - start, end - middle));
            return histories;
        }

        /**
         * Add a commit to all histories of all tests modified by this commit
         */
        private void parseCommit(final RevCommit commit,
                                 final Map<String, List<Revision>> histories,
                                 final Set<ObjectId> visited,
                                 final Queue<RevCommit> queue) throws IOException {
            if (!visited.add(commit.getId())) {
                return;
            }

            final RevCommit[] parents = commit.getParents();
            if (parents.length == 1) {
                final RevCommit parent = revWalk.parseCommit(parents[0].getId());
                // get diff of this commit to its parent, as list of paths
                final List<DiffEntry> diffs = getDiffEntries(commit, parent);
                for (final DiffEntry diff : diffs) {
                    final String changePath = diff.getChangeType().equals(ChangeType.DELETE) ? diff.getOldPath() : diff.getNewPath();
                    final Matcher testNameMatcher = testNamePattern.matcher(changePath);

                    if (testNameMatcher.matches()) {
                        final String testName = testNameMatcher.group(1);
                        if ((activeTests == null) || activeTests.contains(testName)) {
                            final List<Revision> history = histories.computeIfAbsent(testName, x -> new ArrayList<>());

                            history.add(new Revision(
                                    commit.getName(),
                                    commit.getAuthorIdent().toExternalString(),
                                    new Date(Long.valueOf(commit.getCommitTime()) * 1000 /* convert seconds to milliseconds */),
                                    commit.getFullMessage()
                            ));
                        }
                    }
                }

                queue.add(parent);
            } else if (parents.length == 2) {
                /* this is a merge commit, should be skipped **/
                for (final RevCommit parent : parents) {
                    queue.add(revWalk.parseCommit(parent.getId()));
                }
            }
        }

        private List<DiffEntry> getDiffEntries(final RevCommit commit, final RevCommit parent) throws IOException {
            try {
                return DIFF_ENTRIES_CACHE.get(commit.getName(), () -> diffFormatter.scan(parent.getTree(), commit.getTree()));
            } catch (final ExecutionException e) {
                Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
                throw Throwables.propagate(e.getCause());
            }
        }

        @VisibleForTesting
        static void sortByDate(final Map<String, List<Revision>> histories) {
            final Comparator<Revision> comparator = (o1, o2) -> o2.getDate().compareTo(o1.getDate());
            for (final List<Revision> revisions : histories.values()) {
                revisions.sort(comparator);
            }
        }

        /**
         * @return a pattern that if it matches contains the testname in the first group of the matcher
         */
        @VisibleForTesting
        public static Pattern compileTestNamePattern(final String definitionDirectory) {
            return Pattern.compile(definitionDirectory +
                    File.separator + "(\\w+)" + File.separator + FileBasedProctorStore.TEST_DEFINITION_FILENAME);
        }
    }
}
