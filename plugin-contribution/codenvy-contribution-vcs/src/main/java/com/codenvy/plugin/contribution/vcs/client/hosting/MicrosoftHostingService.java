/*
 *  [2012] - [2016] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.plugin.contribution.vcs.client.hosting;

import com.codenvy.plugin.contribution.vcs.client.hosting.dto.HostUser;
import com.codenvy.plugin.contribution.vcs.client.hosting.dto.PullRequest;
import com.codenvy.plugin.contribution.vcs.client.hosting.dto.Repository;
import com.google.gwt.regexp.shared.RegExp;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;

import org.eclipse.che.api.promises.client.Function;
import org.eclipse.che.api.promises.client.FunctionException;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.api.promises.client.PromiseError;
import org.eclipse.che.api.promises.client.js.JsPromiseError;
import org.eclipse.che.api.promises.client.js.Promises;
import org.eclipse.che.api.workspace.shared.dto.UsersWorkspaceDto;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.app.CurrentUser;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.microsoft.client.MicrosoftServiceClient;
import org.eclipse.che.ide.ext.microsoft.shared.dto.MicrosoftPullRequest;
import org.eclipse.che.ide.ext.microsoft.shared.dto.MicrosoftRepository;
import org.eclipse.che.ide.ext.microsoft.shared.dto.MicrosoftUserProfile;
import org.eclipse.che.ide.ext.microsoft.shared.dto.NewMicrosoftPullRequest;
import org.eclipse.che.ide.rest.RestContext;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Objects;

import static org.eclipse.che.ide.ext.microsoft.shared.VstsErrorCodes.PULL_REQUEST_ALREADY_EXISTS;
import static org.eclipse.che.ide.util.ExceptionUtils.getErrorCode;

/**
 * {@link VcsHostingService} implementation for Microsoft VSTS
 *
 * @author Mihail Kuznyetsov
 */
public class MicrosoftHostingService implements VcsHostingService {

    private static final RegExp MICROSOFT_GIT_PATTERN = RegExp.compile("https://([0-9a-zA-Z-_.%]+)\\.visualstudio\\.com/.+/_git/.+");

    private final AppContext             appContext;
    private final DtoFactory             dtoFactory;
    private final MicrosoftServiceClient microsoftClient;
    private final String                 baseUrl;

    @Inject
    public MicrosoftHostingService(@RestContext final String baseUrl,
                                   AppContext appContext,
                                   DtoFactory dtoFactory,
                                   MicrosoftServiceClient microsoftClient) {
        this.baseUrl = baseUrl;
        this.appContext = appContext;
        this.dtoFactory = dtoFactory;
        this.microsoftClient = microsoftClient;
    }

    @Override
    public String getName() {
        return "VSTS";
    }

    @Override
    public String getHost() {
        return "visualstudio.com";
    }

    @Override
    public boolean isHostRemoteUrl(@NotNull String remoteUrl) {
        return MICROSOFT_GIT_PATTERN.test(remoteUrl);
    }

    @Override
    public void getPullRequest(@NotNull String owner, @NotNull String repository, @NotNull String username, @NotNull String branchName,
                               @NotNull AsyncCallback<PullRequest> callback) {
        microsoftClient.getPullRequests(owner, repository);

    }

    @Override
    public Promise<PullRequest> getPullRequest(String owner, String repository, String username, final String branchName) {
        return microsoftClient.getPullRequests(owner, repository)
                              .thenPromise(new Function<List<MicrosoftPullRequest>, Promise<PullRequest>>() {
                                  @Override
                                  public Promise<PullRequest> apply(List<MicrosoftPullRequest> result) throws FunctionException {
                                      for (MicrosoftPullRequest pullRequest : result)
                                          if (pullRequest != null && pullRequest.getSourceRefName().equals(refsHeads(branchName))) {
                                              return Promises.resolve(valueOf(pullRequest));
                                          }
                                      return Promises.reject(JsPromiseError.create(new NoPullRequestException(branchName)));
                                  }
                              });
    }

    @Override
    public void createPullRequest(final String owner,
                                  final String repository,
                                  final String username,
                                  final String headRepository,
                                  final String headBranchName,
                                  final String baseBranchName,
                                  final String title,
                                  final String body,
                                  final AsyncCallback<PullRequest> callback) {
        microsoftClient.createPullRequest(owner, repository, dtoFactory.createDto(NewMicrosoftPullRequest.class)
                                                                       .withTitle(title)
                                                                       .withDescription(body)
                                                                       .withSourceRefName("refs/heads/" + headBranchName)
                                                                       .withTargetRefName("refs/heads/" + baseBranchName))
                       .catchError(new Operation<PromiseError>() {
                           @Override
                           public void apply(PromiseError err) throws OperationException {
                               switch (getErrorCode(err.getCause())) {

                                   case PULL_REQUEST_ALREADY_EXISTS: {
                                       callback.onFailure(new PullRequestAlreadyExistsException(username + ':' + headBranchName));
                                       break;
                                   }
                                   default: {
                                       callback.onFailure(err.getCause());
                                   }
                               }
                           }
                       });
    }

    @Override
    public Promise<PullRequest> createPullRequest(final String owner,
                                                  final String repository,
                                                  final String username,
                                                  final String headRepository,
                                                  final String headBranchName,
                                                  final String baseBranchName,
                                                  final String title,
                                                  final String body) {
        return microsoftClient.createPullRequest(owner, repository, dtoFactory.createDto(NewMicrosoftPullRequest.class)
                                                                              .withTitle(title)
                                                                              .withDescription(body)
                                                                              .withSourceRefName("refs/heads/" + headBranchName)
                                                                              .withTargetRefName("refs/heads/" + baseBranchName))
                              .then(new Function<MicrosoftPullRequest, PullRequest>() {
                                  @Override
                                  public PullRequest apply(MicrosoftPullRequest arg) throws FunctionException {
                                      return valueOf(arg);
                                  }
                              })
                              .catchErrorPromise(new Function<PromiseError, Promise<PullRequest>>() {
                                  @Override
                                  public Promise<PullRequest> apply(PromiseError err) throws FunctionException {
                                      switch (getErrorCode(err.getCause())) {
                                          case PULL_REQUEST_ALREADY_EXISTS:
                                              return Promises.reject(JsPromiseError.create(new PullRequestAlreadyExistsException(
                                                      username + ':' + headBranchName)));
                                          default:
                                              return Promises.reject(err);

                                      }
                                  }
                              });
    }

    @Override
    public void fork(@NotNull String owner, @NotNull String repository, @NotNull AsyncCallback<Repository> callback) {
        callback.onFailure(new UnsupportedOperationException("Fork is not supported for " + getName()));
    }

    @Override
    public Promise<Repository> fork(String owner, String repository) {
        return Promises.reject(JsPromiseError.create("Fork is not supported for " + getName()));
    }

    @Override
    public void getRepository(@NotNull String owner, @NotNull String repository, @NotNull AsyncCallback<Repository> callback) {
        callback.onFailure(new UnsupportedOperationException("This method is not implemented"));
    }

    @Override
    public Promise<Repository> getRepository(String owner, String repositoryName) {
        return microsoftClient.getRepository(owner, repositoryName)
                              .then(new Function<MicrosoftRepository, Repository>() {
                                  @Override
                                  public Repository apply(MicrosoftRepository msRepo) throws FunctionException {
                                      return valueOf(msRepo);
                                  }
                              });
    }

    @Override
    public String getRepositoryNameFromUrl(@NotNull String url) {
        if (url.contains("/_git/")) {
            String[] splitted = url.split("/_git/");
            return splitted[1];
        } else {
            throw new IllegalArgumentException("Unknown VSTS repo URL pattern");
        }
    }

    @Override
    public String getRepositoryOwnerFromUrl(@NotNull String url) {
        if (url.contains("/_git/")) {
            String[] splitted = url.split("/_git/");
            return splitted[0].substring(splitted[0].lastIndexOf('/') + 1);
        } else {
            throw new IllegalArgumentException("Unknown VSTS repo URL pattern");
        }
    }

    @Override
    public void getUserFork(final String user,
                            final String owner,
                            final String repository,
                            final AsyncCallback<Repository> callback) {
        callback.onFailure(new UnsupportedOperationException("User forks is not supported for " + getName()));
    }

    @Override
    public Promise<Repository> getUserFork(final String user,
                                           final String owner,
                                           final String repository) {
        return Promises.reject(JsPromiseError.create("User forks is not supported for " + getName()));
    }

    @Override
    public void getUserInfo(@NotNull AsyncCallback<HostUser> callback) {
        callback.onFailure(new UnsupportedOperationException("This method is not implemented"));
    }

    @Override
    public Promise<HostUser> getUserInfo() {
        return microsoftClient.getUserProfile().then(new Function<MicrosoftUserProfile, HostUser>() {
            @Override
            public HostUser apply(MicrosoftUserProfile microsoftUserProfile) throws FunctionException {
                return dtoFactory.createDto(HostUser.class)
                                 .withId(microsoftUserProfile.getId())
                                 .withLogin(microsoftUserProfile.getEmailAddress())
                                 .withName(microsoftUserProfile.getDisplayName())
                                 .withUrl("none");
            }
        });
    }

    @Override
    public Promise<String> makeSSHRemoteUrl(@NotNull String username, @NotNull String repository) {
        return Promises.reject(JsPromiseError.create(new UnsupportedOperationException("This method is not implemented")));
    }

    @Override
    public Promise<String> makeHttpRemoteUrl(@NotNull String username, @NotNull String repository) {
        return microsoftClient.makeHttpRemoteUrl(username, repository);
    }

    @Override
    public Promise<String> makePullRequestUrl(final String username, final String repository, final String pullRequestNumber) {
        return microsoftClient.makePullRequestUrl(username, repository, pullRequestNumber);
    }

    @Override
    public String formatReviewFactoryUrl(@NotNull String reviewFactoryUrl) {
        return reviewFactoryUrl;
    }

    @Override
    public void authenticate(@NotNull CurrentUser user, @NotNull AsyncCallback<HostUser> callback) {
        callback.onFailure(new UnsupportedOperationException("This method is not implemented"));
    }

    @Override
    public Promise<HostUser> authenticate(CurrentUser user) {
        final UsersWorkspaceDto workspace = this.appContext.getWorkspace();
        if (workspace == null) {
            return Promises.reject(JsPromiseError.create("Error accessing current workspace"));
        }
        final String authUrl = baseUrl
                               + "/oauth/authenticate?oauth_provider=microsoft&userId=" + user.getProfile().getId()
                               + "&scope=vso.code_manage%20vso.code_status&redirect_after_login="
                               + Window.Location.getProtocol() + "//"
                               + Window.Location.getHost() + "/ws/"
                               + workspace.getConfig().getName();
        return ServiceUtil.performWindowAuth(this, authUrl);
    }

    /**
     * Converts an instance of {@link org.eclipse.che.ide.ext.microsoft.shared.dto.MicrosoftRepository} into a {@link
     * com.codenvy.plugin.contribution.vcs.client.hosting.dto.Repository}.
     *
     * @param microsoftRepository
     *         the MicrosoftVstsRestClient repository to convert.
     * @return the corresponding {@link com.codenvy.plugin.contribution.vcs.client.hosting.dto.Repository} instance or {@code null} if given
     * microsoftRepository is {@code null}.
     */
    private Repository valueOf(final MicrosoftRepository microsoftRepository) {
        if (microsoftRepository == null) {
            return null;
        }

        return dtoFactory.createDto(Repository.class)
                         .withFork(false)
                         .withName(microsoftRepository.getName())
                         .withParent(null)
                         .withPrivateRepo(false)
                         .withCloneUrl(microsoftRepository.getUrl());
    }

    /**
     * Converts an instance of {@link org.eclipse.che.ide.ext.microsoft.shared.dto.MicrosoftPullRequest} into a {@link
     * com.codenvy.plugin.contribution.vcs.client.hosting.dto.PullRequest}.
     *
     * @param microsoftPullRequest
     *         the MicrosoftVstsRestClient repository to convert.
     * @return the corresponding {@link com.codenvy.plugin.contribution.vcs.client.hosting.dto.PullRequest} instance or {@code null} if
     * given
     * microsoftRepository is {@code null}.
     */
    private PullRequest valueOf(final MicrosoftPullRequest microsoftPullRequest) {
        if (microsoftPullRequest == null) {
            return null;
        }

        return dtoFactory.createDto(PullRequest.class)
                         .withId(String.valueOf(microsoftPullRequest.getPullRequestId()))
                         .withUrl(microsoftPullRequest.getUrl())
                         .withHtmlUrl("")
                         .withNumber(String.valueOf(microsoftPullRequest.getPullRequestId()))
                         .withState(microsoftPullRequest.getStatus())
                         .withHeadRef(microsoftPullRequest.getSourceRefName());
    }

    private String refsHeads(String branchName) {
        return "refs/heads/" + branchName;
    }
}
