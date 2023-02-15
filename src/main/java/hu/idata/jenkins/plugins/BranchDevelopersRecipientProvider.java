package hu.idata.jenkins.plugins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.User;
import hudson.plugins.emailext.ExtendedEmailPublisherContext;
import hudson.plugins.emailext.ExtendedEmailPublisherDescriptor;
import hudson.plugins.emailext.plugins.RecipientProvider;
import hudson.plugins.emailext.plugins.RecipientProviderDescriptor;
import hudson.plugins.emailext.plugins.recipients.RecipientProviderUtilities;
import hudson.scm.ChangeLogSet;
import jakarta.mail.internet.InternetAddress;
import jenkins.model.Jenkins;
import jenkins.scm.RunWithSCM;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class BranchDevelopersRecipientProvider extends RecipientProvider {
	@DataBoundConstructor
	public BranchDevelopersRecipientProvider() {
	}

	@Override
	public void addRecipients(final @Nonnull ExtendedEmailPublisherContext context,
	                          final @Nonnull EnvVars environmentVariables, final @Nonnull Set<InternetAddress> to,
	                          final @Nonnull Set<InternetAddress> cc, final @Nonnull Set<InternetAddress> bcc) {
		final class Debug implements RecipientProviderUtilities.IDebug {
			private final ExtendedEmailPublisherDescriptor descriptor =
					Jenkins.get().getDescriptorByType(ExtendedEmailPublisherDescriptor.class);

			private final PrintStream logger = context.getListener().getLogger();

			public void send(final String format, final Object... args) {
				if (descriptor != null) {
					descriptor.debug(logger, format, args);
				}
			}
		}

		final Debug debug = new Debug();

		final Set<User> usersToBeNotified = new HashSet<>();

		final Run<?, ?> run = context.getRun();
		if (run == null) {
			debug.send("Cannot find the current run!");
			return;
		}

		final User userTriggeringTheBuild = RecipientProviderUtilities.getUserTriggeringTheBuild(run);
		if (userTriggeringTheBuild != null) {
			usersToBeNotified.add(userTriggeringTheBuild);
		} else {
			debug.send("Cannot determine the user who triggered the build!");
		}

		final String branch = getBranch(environmentVariables, debug);
		final File repositoryPath = getRepositoryPath(context, debug);
		if (repositoryPath != null && branch != null) {
			try (final Git repository = Git.open(repositoryPath)) {
				final Set<User> authors = getAuthorsOfCommitsExclusiveToBranch(run, repository, branch, debug);
				usersToBeNotified.addAll(authors);
			} catch (final IOException exception) {
				debug.send("Cannot access the Git repository: %s", exception);
			}
		}

		RecipientProviderUtilities.addUsers(usersToBeNotified, context, environmentVariables, to, cc, bcc, debug);
	}

	@Nullable
	private static String getBranch(final @Nonnull EnvVars environmentVariables,
	                                final @Nonnull RecipientProviderUtilities.IDebug debug) {
		final String branch = environmentVariables.get("GIT_BRANCH");
		if (branch == null) {
			debug.send("No Git branch is checked out!");
		}

		return branch;
	}

	@Nullable
	private static File getRepositoryPath(final @Nonnull ExtendedEmailPublisherContext context,
	                                      final @Nonnull RecipientProviderUtilities.IDebug debug) {
		final FilePath workspace = context.getWorkspace();
		if (workspace.isRemote()) {
			debug.send("Cannot handle remote workspaces");
			return null;
		}

		final String repositoryPathName = workspace.getRemote();
		if (repositoryPathName == null) {
			debug.send("Cannot get the path of the workspace");
			return null;
		}

		return new File(repositoryPathName);
	}

	@Nonnull
	private static Set<User> getAuthorsOfCommitsExclusiveToBranch(final @Nonnull Run<?, ?> run,
	                                                              final @Nonnull Git repository,
	                                                              final @Nonnull String branch,
	                                                              final @Nonnull RecipientProviderUtilities.IDebug debug) {
		final Set<User> authors = new HashSet<>();

		getChangeLogSets(run, debug).forEach(changeLogSet ->
				addAuthorsOfCommitsExclusiveToBranch(changeLogSet, repository, branch, authors, debug));

		return authors;
	}

	@Nonnull
	private static List<ChangeLogSet<? extends ChangeLogSet.Entry>> getChangeLogSets(final @Nonnull Run<?, ?> run,
	                                                                                 final @Nonnull RecipientProviderUtilities.IDebug debug) {
		if (run instanceof RunWithSCM) {
			final RunWithSCM<?, ?> runWithSCM = (RunWithSCM<?, ?>) run;
			return runWithSCM.getChangeSets();
		}

		debug.send("No SCM associated with this run");
		return List.of();
	}

	private static void addAuthorsOfCommitsExclusiveToBranch(final @Nonnull ChangeLogSet<? extends ChangeLogSet.Entry> changeLogSet,
	                                                         final @Nonnull Git repository, final @Nonnull String branch,
	                                                         final @Nonnull Set<User> authors,
	                                                         final @Nonnull RecipientProviderUtilities.IDebug debug) {
		changeLogSet.forEach(changeLog -> {
			final User author = changeLog.getAuthor();
			if (!authors.contains(author)) {
				final String commitId = changeLog.getCommitId();
				final List<String> branches = getBranchesWhichContainCommit(repository, commitId, debug);

				/* If the commit is present only on the current branch, then the author of the commit should be notified. */
				if (branches.size() == 1 && branch.equals(branches.get(0))) {
					authors.add(author);
				}
			}
		});
	}

	@Nonnull
	private static List<String> getBranchesWhichContainCommit(final @Nonnull Git repository,
	                                                          final @Nonnull String commitId,
	                                                          final @Nonnull RecipientProviderUtilities.IDebug debug) {
		try {
			final List<Ref> branchReferences = repository.branchList().setListMode(ListBranchCommand.ListMode.ALL)
					.setContains(commitId).call();
			return branchReferences.stream()
					.map(BranchDevelopersRecipientProvider::getBranchName)
					.filter(branch -> !Constants.HEAD.equals(branch))
					.collect(Collectors.toList());
		} catch (final GitAPIException exception) {
			debug.send("Cannot get the name of branches which contain commit %s: %s", commitId, exception);
			return List.of();
		}
	}

	private static String getBranchName(final @Nonnull Ref ref) {
		return ref.getName().replace("refs/remotes/", "");
	}

	@Extension
	@Symbol("branchDevelopers")
	public static final class DescriptorImpl extends RecipientProviderDescriptor {
		@Nonnull
		@Override
		public String getDisplayName() {
			return "Developers who worked on the branch";
		}
	}
}
