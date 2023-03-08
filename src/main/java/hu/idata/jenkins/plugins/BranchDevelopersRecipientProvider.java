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
import hudson.scm.ChangeLogSet;
import jakarta.mail.internet.InternetAddress;
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
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
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

		final File repositoryPath = getRepositoryPath(context, debug);
		if (repositoryPath != null) {
			try (final Git repository = Git.open(repositoryPath)) {
				final String branch = getBranch(repository, context, environmentVariables, debug);
				if (branch != null) {
					final Set<User> authors = getAuthorsOfCommitsExclusiveToBranch(run, repository, branch, context);
					usersToBeNotified.addAll(authors);
				}
			} catch (final IOException exception) {
				log(context, "Cannot access the Git repository: %s", exception);
			}
		}

		RecipientProviderUtilities.addUsers(usersToBeNotified, context, environmentVariables, to, cc, bcc, debug);
	}

	@Nullable
	private File getRepositoryPath(final @Nonnull ExtendedEmailPublisherContext context,
	                               final @Nonnull RecipientProviderUtilities.IDebug debug) {
		final String workspacePath;
		if (userSpecifiedWorkspacePath != null) {
			workspacePath = userSpecifiedWorkspacePath;
			debug.send("User-specified workspace path: %s", workspacePath);
		} else {
			workspacePath = getWorkspacePath(context, debug);
			debug.send("Workspace path: %s", workspacePath);
		}

		final String repositoryPath;
		if (userSpecifiedRepositoryPath != null) {
			if (Path.of(userSpecifiedRepositoryPath).isAbsolute()) {
				repositoryPath = userSpecifiedRepositoryPath;
				debug.send("User-specified absolute repository path: %s", repositoryPath);
			} else if (workspacePath != null) {
				repositoryPath = Path.of(workspacePath, userSpecifiedRepositoryPath).toString();
				debug.send("User-specified relative repository path: %s", userSpecifiedRepositoryPath);
			} else {
				repositoryPath = null;
				log(context, "The user-specified repository path is relative, but could not determine the workspace path");
			}
		} else if (workspacePath != null) {
			repositoryPath = workspacePath;
			debug.send("Repository path is the same as the workspace path");
		} else {
			repositoryPath = null;
			log(context, "The workspace and/or repository should be set in the command");
		}

		if (repositoryPath == null) {
			log(context, "Cannot determine repository path");
			return null;
		}

		return new File(repositoryPath);
	}

	@Nullable
	private static String getWorkspacePath(final @Nonnull ExtendedEmailPublisherContext context,
	                                       final @Nonnull RecipientProviderUtilities.IDebug debug) {
		final FilePath workspace = getWorkspace(context);
		if (workspace == null) {
			debug.send("Cannot get the path of the workspace");
			return null;
		}

		if (workspace.isRemote()) {
			log(context, "Cannot handle remote workspaces");
			return null;
		}

		final String workspacePath = workspace.getRemote();
		if (workspacePath == null) {
			log(context, "Cannot get the path of the workspace");
			return null;
		}

		return workspacePath;
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

	@Nullable
	private static String getBranch(final @Nonnull Git repository,
	                                final @Nonnull ExtendedEmailPublisherContext context,
	                                final @Nonnull EnvVars environmentVariables,
	                                final @Nonnull RecipientProviderUtilities.IDebug debug) {
		final String branchFromEnvironment = environmentVariables.get("GIT_BRANCH");
		if (branchFromEnvironment != null) {
			debug.send("Branch from environment: %s", branchFromEnvironment);
			return branchFromEnvironment;
		}

		final List<String> branchesFromRepository = getBranchesWhichContainCommit(repository, Constants.HEAD, context)
				.stream().filter(BranchDevelopersRecipientProvider::isLocalBranch)
				.map(BranchDevelopersRecipientProvider::toRemoteBranch).collect(Collectors.toList());
		if (branchesFromRepository.size() > 0) {
			if (branchesFromRepository.size() > 1) {
				debug.send("Found multiple branches for HEAD: %s", branchesFromRepository);
			}
			debug.send("Branch from repository: %s", branchesFromRepository.get(0));
			return branchesFromRepository.get(0);
		}

		log(context, "Cannot determine the checked out Git branch!");
		return null;
	}

	private static boolean isLocalBranch(final @Nonnull String branch) {
		return branch.startsWith(Constants.R_HEADS);
	}

	private static String toRemoteBranch(final @Nonnull String branch) {
		return branch.replace(Constants.R_HEADS, Constants.DEFAULT_REMOTE_NAME + "/");
	}

	@Nonnull
	private static Set<User> getAuthorsOfCommitsExclusiveToBranch(final @Nonnull Run<?, ?> run,
	                                                              final @Nonnull Git repository,
	                                                              final @Nonnull String branch,
	                                                              final @Nonnull ExtendedEmailPublisherContext context) {
		final Set<User> authors = new HashSet<>();

		getChangeLogSets(run, context).forEach(changeLogSet ->
				addAuthorsOfCommitsExclusiveToBranch(changeLogSet, repository, branch, authors, context));

		return authors;
	}

	@Nonnull
	private static List<ChangeLogSet<? extends ChangeLogSet.Entry>> getChangeLogSets(final @Nonnull Run<?, ?> run,
	                                                                                 final @Nonnull ExtendedEmailPublisherContext context) {
		if (run instanceof RunWithSCM) {
			final RunWithSCM<?, ?> runWithSCM = (RunWithSCM<?, ?>) run;
			return runWithSCM.getChangeSets();
		}

		log(context, "No SCM associated with this run!");
		return List.of();
	}

	private static void addAuthorsOfCommitsExclusiveToBranch(final @Nonnull ChangeLogSet<? extends ChangeLogSet.Entry> changeLogSet,
	                                                         final @Nonnull Git repository, final @Nonnull String branch,
	                                                         final @Nonnull Set<User> authors,
	                                                         final @Nonnull ExtendedEmailPublisherContext context) {
		changeLogSet.forEach(changeLog -> {
			final User author = changeLog.getAuthor();
			if (!authors.contains(author)) {
				final String commitId = changeLog.getCommitId();
				final List<String> branches = getBranchesWhichContainCommit(repository, commitId, context);

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
	                                                          final @Nonnull ExtendedEmailPublisherContext context) {
		try {
			final List<Ref> branchReferences = repository.branchList().setListMode(ListBranchCommand.ListMode.ALL)
					.setContains(commitId).call();
			return branchReferences.stream()
					.map(BranchDevelopersRecipientProvider::getBranchName)
					.filter(branch -> !Constants.HEAD.equals(branch))
					.collect(Collectors.toList());
		} catch (final GitAPIException exception) {
			log(context, "Cannot get the name of branches which contain commit %s: %s", commitId, exception);
			return List.of();
		}
	}

	private static String getBranchName(final @Nonnull Ref ref) {
		return ref.getName().replace("refs/remotes/", "");
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
}
