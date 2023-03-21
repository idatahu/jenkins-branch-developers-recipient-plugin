package hu.idata.jenkins.plugins;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.User;
import hudson.plugins.emailext.ExtendedEmailPublisherContext;
import hudson.plugins.emailext.ExtendedEmailPublisherDescriptor;
import hudson.plugins.emailext.plugins.RecipientProvider;
import hudson.plugins.emailext.plugins.RecipientProviderDescriptor;
import hudson.plugins.emailext.plugins.recipients.RecipientProviderUtilities;
import hudson.remoting.VirtualChannel;
import hudson.scm.ChangeLogSet;
import jakarta.mail.internet.InternetAddress;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;
import jenkins.scm.RunWithSCM;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class BranchDevelopersRecipientProvider extends RecipientProvider {
	private @Nullable String userSpecifiedWorkspacePath;
	private @Nullable String userSpecifiedRepositoryPath;

	@DataBoundConstructor
	public BranchDevelopersRecipientProvider() {
	}

	@DataBoundSetter
	public void setWorkspace(final @Nullable String workspace) {
		if (StringUtils.isNotBlank(workspace)) {
			userSpecifiedWorkspacePath = workspace;
		}
	}

	@DataBoundSetter
	public void setRepository(final @Nullable String repository) {
		if (StringUtils.isNotBlank(repository)) {
			userSpecifiedRepositoryPath = repository;
		}
	}

	@Override
	public void addRecipients(final @Nonnull ExtendedEmailPublisherContext context,
	                          final @Nonnull EnvVars environmentVariables, final @Nonnull Set<InternetAddress> to,
	                          final @Nonnull Set<InternetAddress> cc, final @Nonnull Set<InternetAddress> bcc) {
		final class Debug implements RecipientProviderUtilities.IDebug {
			private final ExtendedEmailPublisherDescriptor descriptor =
					Jenkins.get().getDescriptorByType(ExtendedEmailPublisherDescriptor.class);

			private final PrintStream logger = context.getListener().getLogger();

			public void send(final String format, final Object... arguments) {
				if (descriptor != null) {
					descriptor.debug(logger, format, arguments);
				}
			}
		}

		final Debug debug = new Debug();

		final Set<User> usersToBeNotified = new HashSet<>();

		final Run<?, ?> run = context.getRun();
		if (run == null) {
			log(context, "Cannot find the current run!");
			return;
		}

		final User userTriggeringTheBuild = RecipientProviderUtilities.getUserTriggeringTheBuild(run);
		if (userTriggeringTheBuild != null) {
			usersToBeNotified.add(userTriggeringTheBuild);
		} else {
			debug.send("Cannot determine the user who triggered the build!");
		}

		final FilePath repositoryPath = getRepositoryPath(context, debug);
		if (repositoryPath != null) {
			try {
				final List<ChangeLogSet.Entry> changeLogEntries = getChangeLogEntries(context);
				final List<String> commitIds = changeLogEntries.stream().map(ChangeLogSet.Entry::getCommitId)
						.collect(Collectors.toList());
				final String branchFromEnvironment = getBranchFromEnvironment(environmentVariables, debug);

				final BranchDevelopersRecipientCallable remoteOperation =
						new BranchDevelopersRecipientCallable(commitIds, branchFromEnvironment);
				final List<String> commitIdsExclusiveToBranch = repositoryPath.act(remoteOperation);

				final List<User> authorsExclusiveToBranch = commitIdsExclusiveToBranch.stream()
						.map(commitId -> getChangeLogEntry(changeLogEntries, commitId, context))
						.filter(Objects::nonNull)
						.map(ChangeLogSet.Entry::getAuthor)
						.collect(Collectors.toList());
				usersToBeNotified.addAll(authorsExclusiveToBranch);
			} catch (final IOException exception) {
				log(context, "Cannot execute Git commands on the remote host: %s", exception);
			} catch (final InterruptedException exception) {
				log(context, "Interrupted execution of Git commands on the remote host: %s", exception);
			}
		}

		RecipientProviderUtilities.addUsers(usersToBeNotified, context, environmentVariables, to, cc, bcc, debug);
	}

	@Nullable
	private FilePath getRepositoryPath(final @Nonnull ExtendedEmailPublisherContext context,
	                                   final @Nonnull RecipientProviderUtilities.IDebug debug) {
		final FilePath workspace;
		if (userSpecifiedWorkspacePath != null) {
			final File workspaceFile = new File(userSpecifiedWorkspacePath);
			workspace = new FilePath(workspaceFile);
			debug.send("User-specified workspace path: %s", workspace);
		} else {
			workspace = getWorkspace(context);
			if (workspace == null) {
				debug.send("Cannot get the path of the workspace");
				return null;
			}

			debug.send("Workspace: %s", workspace);
		}

		final FilePath repository;
		if (userSpecifiedRepositoryPath != null) {
			repository = new FilePath(workspace, userSpecifiedRepositoryPath);
			debug.send("User-specified repository path: %s", repository);
		} else {
			repository = workspace;
			debug.send("Repository path is the same as the workspace path");
		}

		return repository;
	}

	@Nullable
	private static FilePath getWorkspace(final @Nonnull ExtendedEmailPublisherContext context) {
		final FilePath workspaceFromContext = context.getWorkspace();
		if (workspaceFromContext != null) {
			return workspaceFromContext;
		}

		final Run<?, ?> run  = context.getRun();
		if (run instanceof AbstractBuild) {
			final AbstractBuild<?, ?> abstractBuild = (AbstractBuild<?, ?>) run;
			return abstractBuild.getWorkspace();
		}

		return null;
	}

	@Nonnull
	private static List<ChangeLogSet.Entry> getChangeLogEntries(final @Nonnull ExtendedEmailPublisherContext context) {
		final Run<?, ?> run = context.getRun();
		if (run instanceof RunWithSCM) {
			final RunWithSCM<?, ?> runWithSCM = (RunWithSCM<?, ?>) run;
			return runWithSCM.getChangeSets().stream()
					.map(BranchDevelopersRecipientProvider::getChangeLogEntries)
					.flatMap(List::stream).collect(Collectors.toList());
		}

		log(context, "No SCM associated with this run!");
		return List.of();
	}

	@Nonnull
	private static List<ChangeLogSet.Entry> getChangeLogEntries(final @Nonnull ChangeLogSet<? extends ChangeLogSet.Entry> changeLogSet) {
		final List<ChangeLogSet.Entry> changeLogEntries = new ArrayList<>();
		changeLogSet.forEach(changeLogEntries::add);
		return changeLogEntries;
	}

	private static String getBranchFromEnvironment(EnvVars environmentVariables, RecipientProviderUtilities.IDebug debug) {
		final String branchFromEnvironment = environmentVariables.get("GIT_BRANCH");
		if (branchFromEnvironment != null) {
			debug.send("Branch from environment: %s", branchFromEnvironment);
			return branchFromEnvironment;
		}
		return null;
	}

	@Nullable
	private ChangeLogSet.Entry getChangeLogEntry(final @Nonnull List<ChangeLogSet.Entry> changeLogEntries,
	                                             final @Nonnull String commitId,
	                                             final @Nonnull ExtendedEmailPublisherContext context) {
		final List<ChangeLogSet.Entry> changeLogEntriesForCommitId = changeLogEntries.stream()
				.filter(changeLogEntry -> commitId.equals(changeLogEntry.getCommitId())).collect(Collectors.toList());
		if (changeLogEntriesForCommitId.isEmpty()) {
			log(context, "Cannot find changelog entry for commit %s", commitId);
			return null;
		}
		return null;
	}

	private static void log(final @Nonnull ExtendedEmailPublisherContext context, final @Nonnull String message,
	                        final Object... arguments) {
		final PrintStream logger = context.getListener().getLogger();
		logger.format(message, arguments);
		logger.println();
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

	private static class BranchDevelopersRecipientCallable extends MasterToSlaveFileCallable<List<String>> {
		private static final long serialVersionUID = 1L;
		private final @Nonnull List<String> commitIds;
		private final @Nullable String branchFromEnvironment;
		private final @Nonnull List<String> logMessages;

		private BranchDevelopersRecipientCallable(final @Nonnull List<String> commitIds,
		                                          final @Nullable String branchFromEnvironment) {
			this.commitIds = commitIds;
			this.branchFromEnvironment = branchFromEnvironment;
			this.logMessages = new ArrayList<>();
		}

		@Override
		@Nonnull
		public List<String> invoke(final @Nonnull File repositoryPath, final @Nullable VirtualChannel channel)
				throws IOException, InterruptedException {
			log("Invoked in %s", repositoryPath);
			try (final Git repository = Git.open(repositoryPath)) {
				final String branch = getBranch(repository);
				if (branch != null) {
					return getCommitsExclusiveToBranch(repository, branch, commitIds);
				}
			} catch (final IOException exception) {
				log("Cannot access the Git repository: %s", exception);
			}

			return List.of();
		}

		@Nullable
		private String getBranch(final @Nonnull Git repository) {
			if (branchFromEnvironment != null) {
				return branchFromEnvironment;
			}

			final List<String> branchesFromRepository = getBranchesWhichContainCommit(repository, Constants.HEAD)
					.stream().filter(BranchDevelopersRecipientCallable::isLocalBranch)
					.map(BranchDevelopersRecipientCallable::toRemoteBranch).collect(Collectors.toList());
			if (branchesFromRepository.size() > 0) {
				if (branchesFromRepository.size() > 1) {
					log("Found multiple branches for HEAD: %s", branchesFromRepository);
				}
				log("Branch from repository: %s", branchesFromRepository.get(0));
				return branchesFromRepository.get(0);
			}

			log("Cannot determine the checked out Git branch!");
			return null;
		}

		@Nonnull
		private List<String> getBranchesWhichContainCommit(final @Nonnull Git repository,
		                                                   final @Nonnull String commitId) {
			try {
				final List<Ref> branchReferences = repository.branchList().setListMode(ListBranchCommand.ListMode.ALL)
						.setContains(commitId).call();
				return branchReferences.stream()
						.map(BranchDevelopersRecipientCallable::getBranchName)
						.filter(branch -> !Constants.HEAD.equals(branch))
						.collect(Collectors.toList());
			} catch (final GitAPIException exception) {
				log("Cannot get the name of branches which contain commit %s: %s", commitId, exception);
				return List.of();
			}
		}

		@Nonnull
		private List<String> getCommitsExclusiveToBranch(final @Nonnull Git repository, final @Nonnull String branch,
		                                                 final @Nonnull List<String> commitIds) {
			return commitIds.stream().filter(commitId -> {
				final List<String> branches = getBranchesWhichContainCommit(repository, commitId);
				/* If the commit is present only on the current branch, then the author of the commit should be notified. */
				return branches.size() == 1 && branch.equals(branches.get(0));
			}).collect(Collectors.toList());
		}

		private static boolean isLocalBranch(final @Nonnull String branch) {
			return branch.startsWith(Constants.R_HEADS);
		}

		private static String toRemoteBranch(final @Nonnull String branch) {
			return branch.replace(Constants.R_HEADS, Constants.DEFAULT_REMOTE_NAME + "/");
		}

		private static String getBranchName(final @Nonnull Ref ref) {
			return ref.getName().replace("refs/remotes/", "");
		}

		private void log(final String format, final Object... arguments) {
			logMessages.add(String.format(format, arguments));
		}
	}
}
