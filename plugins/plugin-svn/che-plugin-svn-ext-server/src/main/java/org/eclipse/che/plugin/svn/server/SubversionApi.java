/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.plugin.svn.server;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.common.net.MediaType;
import com.google.inject.Singleton;

import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.util.LineConsumerFactory;
import org.eclipse.che.api.vfs.util.DeleteOnCloseFileInputStream;
import org.eclipse.che.commons.lang.IoUtil;
import org.eclipse.che.commons.lang.ZipUtils;
import org.eclipse.che.dto.server.DtoFactory;
import org.eclipse.che.plugin.ssh.key.script.SshScriptProvider;
import org.eclipse.che.plugin.svn.server.credentials.CredentialsException;
import org.eclipse.che.plugin.svn.server.credentials.CredentialsProvider;
import org.eclipse.che.plugin.svn.server.credentials.CredentialsProvider.Credentials;
import org.eclipse.che.plugin.svn.server.repository.RepositoryUrlProvider;
import org.eclipse.che.plugin.svn.server.upstream.CommandLineResult;
import org.eclipse.che.plugin.svn.server.upstream.UpstreamUtils;
import org.eclipse.che.plugin.svn.server.utils.InfoUtils;
import org.eclipse.che.plugin.svn.server.utils.SshEnvironment;
import org.eclipse.che.plugin.svn.server.utils.SubversionUtils;
import org.eclipse.che.plugin.svn.shared.AddRequest;
import org.eclipse.che.plugin.svn.shared.CLIOutputResponse;
import org.eclipse.che.plugin.svn.shared.CLIOutputResponseList;
import org.eclipse.che.plugin.svn.shared.CLIOutputWithRevisionResponse;
import org.eclipse.che.plugin.svn.shared.CheckoutRequest;
import org.eclipse.che.plugin.svn.shared.CleanupRequest;
import org.eclipse.che.plugin.svn.shared.CommitRequest;
import org.eclipse.che.plugin.svn.shared.CopyRequest;
import org.eclipse.che.plugin.svn.shared.GetRevisionsRequest;
import org.eclipse.che.plugin.svn.shared.GetRevisionsResponse;
import org.eclipse.che.plugin.svn.shared.InfoRequest;
import org.eclipse.che.plugin.svn.shared.InfoResponse;
import org.eclipse.che.plugin.svn.shared.ListRequest;
import org.eclipse.che.plugin.svn.shared.ListResponse;
import org.eclipse.che.plugin.svn.shared.LockRequest;
import org.eclipse.che.plugin.svn.shared.MergeRequest;
import org.eclipse.che.plugin.svn.shared.MoveRequest;
import org.eclipse.che.plugin.svn.shared.PropertyDeleteRequest;
import org.eclipse.che.plugin.svn.shared.PropertyGetRequest;
import org.eclipse.che.plugin.svn.shared.PropertyListRequest;
import org.eclipse.che.plugin.svn.shared.PropertySetRequest;
import org.eclipse.che.plugin.svn.shared.RemoveRequest;
import org.eclipse.che.plugin.svn.shared.ResolveRequest;
import org.eclipse.che.plugin.svn.shared.RevertRequest;
import org.eclipse.che.plugin.svn.shared.ShowDiffRequest;
import org.eclipse.che.plugin.svn.shared.ShowLogRequest;
import org.eclipse.che.plugin.svn.shared.StatusRequest;
import org.eclipse.che.plugin.svn.shared.SubversionItem;
import org.eclipse.che.plugin.svn.shared.UpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Strings.isNullOrEmpty;

/**
 * Provides Subversion APIs.
 */
@Singleton
public class SubversionApi {

    private static Logger LOG = LoggerFactory.getLogger(SubversionApi.class);

    private final CredentialsProvider   credentialsProvider;
    private final RepositoryUrlProvider repositoryUrlProvider;
    private final SshScriptProvider     sshScriptProvider;
    protected     LineConsumerFactory   svnOutputPublisherFactory;

    @Inject
    public SubversionApi(CredentialsProvider credentialsProvider,
                         RepositoryUrlProvider repositoryUrlProvider,
                         SshScriptProvider sshScriptProvider) {
        this.credentialsProvider = credentialsProvider;
        this.repositoryUrlProvider = repositoryUrlProvider;
        this.sshScriptProvider = sshScriptProvider;
    }

    /**
     * Set up std output consumer.
     *
     * @param svnOutputPublisherFactory
     *         std output line consumer factory.
     */
    public void setOutputLineConsumerFactory(LineConsumerFactory svnOutputPublisherFactory) {
        this.svnOutputPublisherFactory = svnOutputPublisherFactory;
    }

    /**
     * Perform an "svn add" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse add(final AddRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> args = defaultArgs();

        // Flags
        addFlag(args, "--no-ignore", request.isAddIgnored());
        addFlag(args, "--parents", request.isAddParents());

        if (request.isAutoProps()) {
            args.add("--auto-props");
        }

        if (request.isNotAutoProps()) {
            args.add("--no-auto-props");
        }

        // Options
        addOption(args, "--depth", request.getDepth());

        // Command Name
        args.add("add");

        // Command Arguments

        final CommandLineResult result = runCommand(null, args, projectPath, request.getPaths());

        return DtoFactory.getInstance()
                         .createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }


    /**
     * Perform an "svn revert" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse revert(RevertRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> cliArgs = defaultArgs();

        addOption(cliArgs, "--depth", request.getDepth());

        cliArgs.add("revert");

        final CommandLineResult result = runCommand(null, cliArgs, projectPath, addWorkingCopyPathIfNecessary(request.getPaths()));

        return DtoFactory.getInstance()
                         .createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn copy" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse copy(final CopyRequest request) throws IOException, SubversionException {

        //for security reason we should forbid file protocol
        if (request.getSource().startsWith("file://") || request.getDestination().startsWith("file://")) {
            throw new SubversionException("Url is not acceptable");
        }

        final File projectPath = new File(request.getProjectPath());

        final List<String> cliArgs = defaultArgs();

        if (!isNullOrEmpty(request.getComment())) {
            addOption(cliArgs, "--message", "\"" + request.getComment() + "\"");
        }

        // Command Name
        cliArgs.add("copy");

        final CommandLineResult result =
                runCommand(null, cliArgs, projectPath, Arrays.asList(request.getSource(), request.getDestination()));

        return DtoFactory.getInstance()
                         .createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    public CLIOutputWithRevisionResponse checkout(final CheckoutRequest request)
            throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());
        final List<String> cliArgs = defaultArgs();

        // Flags
        addFlag(cliArgs, "--ignore-externals", request.isIgnoreExternals());

        // Options
        addOption(cliArgs, "--depth", request.getDepth());
        addOption(cliArgs, "--revision", request.getRevision());

        // Command Name
        cliArgs.add("checkout");

        // Command Arguments
        cliArgs.add(request.getUrl());
        cliArgs.add(projectPath.getAbsolutePath());

        String login = request.getLogin();
        String password = request.getPassword();
        String[] credentials = null;
        if (!isNullOrEmpty(login) && !isNullOrEmpty(password)) {
            credentials = new String[] {login, password};
        }
        CommandLineResult result = runCommand(null, cliArgs, projectPath, request.getPaths(), credentials, request.getUrl());

        return DtoFactory.getInstance().createDto(CLIOutputWithRevisionResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr())
                         .withRevision(SubversionUtils.getCheckoutRevision(result.getStdout()));
    }

    /**
     * Perform an "svn commit" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputWithRevisionResponse commit(final CommitRequest request)
            throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> cliArgs = defaultArgs();

        // Flags
        addFlag(cliArgs, "--keep-changelists", request.isKeepChangeLists());
        addFlag(cliArgs, "--no-unlock", request.isKeepLocks());

        // Command Name
        cliArgs.add("commit");

        // Command Arguments
        cliArgs.add("-m");
        cliArgs.add(request.getMessage());

        final CommandLineResult result = runCommand(null, cliArgs, projectPath,
                                                    addWorkingCopyPathIfNecessary(request.getPaths()));

        return DtoFactory.getInstance().createDto(CLIOutputWithRevisionResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withRevision(SubversionUtils.getCommitRevision(result.getStdout()))
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn remove" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse remove(final RemoveRequest request)
            throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> cliArgs = defaultArgs();

        // Command Name
        cliArgs.add("remove");

        final CommandLineResult result = runCommand(null, cliArgs, projectPath, request.getPaths());

        return DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn status" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse status(final StatusRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> cliArgs = defaultArgs();

        // Flags
        addFlag(cliArgs, "--ignore-externals", request.isIgnoreExternals());
        addFlag(cliArgs, "--no-ignore", request.isShowIgnored());
        addFlag(cliArgs, "--quiet", !request.isShowUnversioned());
        addFlag(cliArgs, "--show-updates", request.isShowUpdates());
        addFlag(cliArgs, "--verbose", request.isVerbose());

        // Options
        addOptionList(cliArgs, "--changelist", request.getChangeLists());
        addOption(cliArgs, "--depth", request.getDepth());

        // Command Name
        cliArgs.add("status");

        final CommandLineResult result = runCommand(null, cliArgs, projectPath,
                                                    addWorkingCopyPathIfNecessary(request.getPaths()));

        return DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn checkout" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputWithRevisionResponse update(final UpdateRequest request)
            throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> uArgs = defaultArgs();

        // Flags
        addFlag(uArgs, "--ignore-externals", request.isIgnoreExternals());

        // Options
        addOption(uArgs, "--depth", request.getDepth());
        addOption(uArgs, "--revision", request.getRevision());

        // Command Name
        uArgs.add("update");

        final CommandLineResult result = runCommand(null, uArgs, projectPath,
                                                    addWorkingCopyPathIfNecessary(request.getPaths()));

        return DtoFactory.getInstance().createDto(CLIOutputWithRevisionResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withRevision(SubversionUtils.getUpdateRevision(result.getStdout()))
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn log" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse showLog(final ShowLogRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> uArgs = defaultArgs();

        addOption(uArgs, "--revision", request.getRevision());
        uArgs.add("log");

        final CommandLineResult result = runCommand(null, uArgs, projectPath, request.getPaths());

        return DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    public CLIOutputResponse lockUnlock(final LockRequest request, final boolean lock) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> args = defaultArgs();

        addFlag(args, "--force", request.isForce());

        // command
        if (lock) {
            args.add("lock");
        } else {
            args.add("unlock");
        }

        final CommandLineResult result = runCommand(null, args, projectPath, request.getTargets());

        return DtoFactory.getInstance()
                         .createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn diff" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse showDiff(final ShowDiffRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> uArgs = defaultArgs();

        addOption(uArgs, "--revision", request.getRevision());
        uArgs.add("diff");

        final CommandLineResult result = runCommand(null, uArgs, projectPath, request.getPaths());

        return DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * List remote subversion directory.
     *
     * @return the response containing target children
     */
    public ListResponse list(final ListRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());
        final List<String> args = defaultArgs();

        args.add("list");

        List<String> paths = new ArrayList<>();
        paths.add(request.getTarget());

        final CommandLineResult result = runCommand(null, args, projectPath, paths);

        return DtoFactory.getInstance().createDto(ListResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrorOutput(result.getStderr());
    }

    /**
     * Perform an "svn resolve" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponseList resolve(final ResolveRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        Map<String, String> resolutions = request.getConflictResolutions();

        List<CLIOutputResponse> results = new ArrayList<>();
        for (String path : resolutions.keySet()) {
            final List<String> uArgs = defaultArgs();

            addDepth(uArgs, request.getDepth());
            addOption(uArgs, "--accept", resolutions.get(path));
            uArgs.add("resolve");

            final CommandLineResult result = runCommand(null, uArgs, projectPath, Arrays.asList(path));

            CLIOutputResponse outputResponse = DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                                                         .withCommand(result.getCommandLine().toString())
                                                         .withOutput(result.getStdout())
                                                         .withErrOutput(result.getStderr());
            results.add(outputResponse);
        }

        return DtoFactory.getInstance().createDto(CLIOutputResponseList.class)
                         .withCLIOutputResponses(results);
    }

    /**
     * Perform an "svn export" based on the request.
     *
     * @param projectPath
     *         project path
     * @param path
     *         exported path
     * @param revision
     *         specified revision to export
     * @return Response which contains hyperlink with download url
     * @throws IOException
     *         if there is a problem executing the command
     * @throws ServerException
     *         if there is an exporting issue
     */
    public Response exportPath(@NotNull final String projectPath, @NotNull final String path, String revision)
            throws IOException, ServerException {

        final File project = new File(projectPath);

        final List<String> uArgs = defaultArgs();

        if (!isNullOrEmpty(revision)) {
            addOption(uArgs, "--revision", revision);
        }

        uArgs.add("--force");
        uArgs.add("export");

        File tempDir = null;
        File zip = null;

        try {
            tempDir = Files.createTempDir();
            final CommandLineResult result = runCommand(null, uArgs, project, Arrays.asList(path, tempDir.getAbsolutePath()));
            if (result.getExitCode() != 0) {
                LOG.warn("Svn export process finished with exit status {}", result.getExitCode());
                throw new ServerException("Export failed");
            }

            zip = new File(Files.createTempDir(), "export.zip");
            ZipUtils.zipDir(tempDir.getPath(), tempDir, zip, IoUtil.ANY_FILTER);
        } finally {
            if (tempDir != null) {
                IoUtil.deleteRecursive(tempDir);
            }
        }

        final Response.ResponseBuilder responseBuilder = Response
                .ok(new DeleteOnCloseFileInputStream(zip), MediaType.ZIP.toString())
                .lastModified(new Date(zip.lastModified()))
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(zip.length()))
                .header("Content-Disposition", "attachment; filename=\"export.zip\"");

        return responseBuilder.build();
    }

    /**
     * Perform an "svn move" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws SubversionException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse move(final MoveRequest request) throws IOException, SubversionException {

        Predicate<String> sourcePredicate = new Predicate<String>() {
            @Override
            public boolean apply(String input) {
                return input.startsWith("file://");
            }
        };

        //for security reason we should forbid file protocol
        if (Iterables.any(request.getSource(), sourcePredicate) || request.getDestination().startsWith("file://")) {
            throw new SubversionException("Url is not acceptable");
        }

        final File projectPath = new File(request.getProjectPath());

        final List<String> cliArgs = defaultArgs();

        if (!isNullOrEmpty(request.getComment())) {
            addOption(cliArgs, "--message", "\"" + request.getComment() + "\"");
        }

        // Command Name
        cliArgs.add("move");

        final List<String> paths = new ArrayList<>();
        paths.addAll(request.getSource());
        paths.add(request.getDestination());

        final CommandLineResult result = runCommand(null, cliArgs, projectPath, paths);

        return DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn propset" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws ServerException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse propset(final PropertySetRequest request) throws IOException, ServerException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> uArgs = defaultArgs();

        if (request.isForce()) {
            uArgs.add("--force");
        }

        addDepth(uArgs, request.getDepth().getValue());

        uArgs.add("propset");
        uArgs.add(request.getName());

        String value = request.getValue();
        Path valueFile = null;
        if (value.contains("\n")) {
            try {
                valueFile = java.nio.file.Files.createTempFile("svn-propset-value-", null);
                java.nio.file.Files.write(valueFile, value.getBytes());
                uArgs.add("-F");
                uArgs.add(valueFile.toString());
            } catch (IOException e) {
                uArgs.add(value);
            }
        } else {
            uArgs.add(value);
        }

        final CommandLineResult result = runCommand(null, uArgs, projectPath, Arrays.asList(request.getPath()));

        return DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn propdel" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws ServerException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse propdel(final PropertyDeleteRequest request) throws IOException, ServerException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> uArgs = defaultArgs();

        addDepth(uArgs, request.getDepth().getValue());

        uArgs.add("propdel");
        uArgs.add(request.getName());

        final CommandLineResult result = runCommand(null, uArgs, projectPath, Arrays.asList(request.getPath()));

        return DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    /**
     * Perform an "svn propget" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws ServerException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse propget(final PropertyGetRequest request) throws IOException, ServerException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> uArgs = defaultArgs();

        uArgs.add("propget");
        uArgs.add(request.getName());

        final CommandLineResult result = runCommand(null, uArgs, projectPath, Arrays.asList(request.getPath()));

        return DtoFactory.getInstance()
                         .createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout());
    }

    /**
     * Perform an "svn proplist" based on the request.
     *
     * @param request
     *         the request
     * @return the response
     * @throws IOException
     *         if there is a problem executing the command
     * @throws ServerException
     *         if there is a Subversion issue
     */
    public CLIOutputResponse proplist(final PropertyListRequest request) throws IOException, ServerException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> uArgs = defaultArgs();

        uArgs.add("proplist");

        final CommandLineResult result = runCommand(null, uArgs, projectPath, Arrays.asList(request.getPath()));

        List<String> output;
        if (result.getStdout() != null && result.getStdout().size() > 0) {
            output = result.getStdout().subList(1, result.getStdout().size());
        } else {
            output = result.getStdout();
        }

        return DtoFactory.getInstance()
                         .createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(output);
    }

    private static void addDepth(final List<String> args, final String depth) {
        if (depth != null && !depth.isEmpty()) {
            args.add("--depth");
            args.add(depth);
        }
    }

    /** Adds flag to arguments when value is true. */
    private void addFlag(final List<String> args, final String argName, final boolean value) {
        if (value) {
            args.add(argName);
        }
    }

    /** Adds an option to arguments. */
    private void addOption(final List<String> args, final String optName, final String value) {
        if (value != null && !value.isEmpty()) {
            args.add(optName);
            args.add(value);
        }
    }

    /** Adds multivalued option to arguments. */
    private void addOptionList(final List<String> args, final String optName, final List<String> values) {
        for (final String value : values) {
            if (value != null && !value.isEmpty()) {
                args.add(optName);
                args.add(value);
            }
        }
    }

    /**
     * Creates a list of arguments containing default values.
     *
     * @return list of arguments
     */
    private List<String> defaultArgs() {
        List<String> args = new ArrayList<>();

//        args.add("--no-auth-cache");
        args.add("--non-interactive");
        args.add("--trust-server-cert");

        return args;
    }

    private List<String> addWorkingCopyPathIfNecessary(List<String> paths) {
        if (paths == null) {
            paths = new ArrayList<>();
        }

        // If there are no paths, add the working copy root to the list of paths
        if (paths.isEmpty()) {
            paths.add(".");
        }

        return paths;
    }

    private CommandLineResult runCommand(Map<String, String> env,
                                         List<String> args,
                                         File projectPath,
                                         List<String> paths) throws IOException, SubversionException {
        String[] credentials = getCredentialArgs(projectPath.getAbsolutePath());
        String repoUrl = getRepositoryUrl(projectPath.getAbsolutePath());
        return runCommand(env, args, projectPath, paths, credentials, repoUrl);
    }

    private CommandLineResult runCommand(Map<String, String> env,
                                         List<String> args,
                                         File projectPath,
                                         List<String> paths,
                                         String[] credentials,
                                         String repoUrl) throws IOException, SubversionException {
        final List<String> lines = new ArrayList<>();
        final CommandLineResult result;
        final StringBuffer buffer;
        boolean isWarning = false;

        // Add paths to the end of the list of arguments
        for (final String path : paths) {
            args.add(path);
        }

        String[] credentialsArgs;
        if (credentials != null && credentials.length == 2) {
            credentialsArgs = new String[]{"--username", credentials[0], "--password", credentials[1]};
        } else {
            credentialsArgs = null;
        }

        SshEnvironment sshEnvironment = null;
        if (SshEnvironment.isSSH(repoUrl)) {
            sshEnvironment = new SshEnvironment(sshScriptProvider, repoUrl);
            if (env == null) {
                env = new HashMap<>();
            }
            env.putAll(sshEnvironment.get());
        }

        try {
            result = UpstreamUtils.executeCommandLine(env,
                                                      "svn",
                                                      args.toArray(new String[args.size()]),
                                                      credentialsArgs,
                                                      -1,
                                                      projectPath,
                                                      svnOutputPublisherFactory);
        } finally {
            if (sshEnvironment != null) {
                sshEnvironment.cleanUp();
            }
        }

        if (result.getExitCode() != 0) {
            buffer = new StringBuffer();

            lines.addAll(result.getStdout());
            lines.addAll(result.getStderr());

            for (final String line : lines) {
                // Subversion returns an error code of 1 even when the "error" is just a warning
                if (line.startsWith("svn: warning: ")) {
                    isWarning = true;
                }

                buffer.append(line);
                buffer.append("\n");
            }

            if (!isWarning) {
                throw new SubversionException(buffer.toString());
            }
        }

        return result;
    }

    private String[] getCredentialArgs(final String projectPath) throws SubversionException, IOException {
        Credentials credentials;
        try {
            credentials = this.credentialsProvider.getCredentials(getRepositoryUrl(projectPath));
        } catch (final CredentialsException e) {
            credentials = null;
        }
        if (credentials != null) {
            return new String[]{credentials.getUsername(), credentials.getPassword()};
        } else {
            return null;
        }
    }

    public String getRepositoryUrl(final String projectPath) throws SubversionException, IOException {
        return this.repositoryUrlProvider.getRepositoryUrl(projectPath);
    }

    /**
     * Returns information about specified target.
     *
     * @param request
     *         request
     * @return response containing list of subversion items
     * @throws SubversionException
     * @throws IOException
     */
    public InfoResponse info(final InfoRequest request) throws SubversionException, IOException {
        final List<String> args = defaultArgs();

        if (request.getRevision() != null && !request.getRevision().trim().isEmpty()) {
            addOption(args, "--revision", request.getRevision());
        }

        if (request.getChildren()) {
            addOption(args, "--depth", "immediates");
        }

        args.add("info");

        List<String> paths = new ArrayList<String>();
        paths.add(request.getTarget());
        final CommandLineResult result = runCommand(null, args, new File(request.getProjectPath()),
                                                    addWorkingCopyPathIfNecessary(paths));

        final InfoResponse response = DtoFactory.getInstance().createDto(InfoResponse.class)
                                                .withCommand(result.getCommandLine().toString())
                                                .withOutput(result.getStdout())
                                                .withErrorOutput(result.getStderr());

        if (result.getExitCode() == 0) {
            List<SubversionItem> items = new ArrayList<SubversionItem>();
            response.withItems(items);

            Iterator<String> iterator = result.getStdout().iterator();
            List<String> itemProperties = new ArrayList<String>();

            while (iterator.hasNext()) {
                String propertyLine = iterator.next();

                if (propertyLine.isEmpty()) {
                    // create Subversion item filling properties from the list
                    final SubversionItem item = DtoFactory.getInstance().createDto(SubversionItem.class)
                                                          .withPath(InfoUtils.getPath(itemProperties))
                                                          .withName(InfoUtils.getName(itemProperties))
                                                          .withURL(InfoUtils.getUrl(itemProperties))
                                                          .withRelativeURL(InfoUtils.getRelativeUrl(itemProperties))
                                                          .withRepositoryRoot(InfoUtils.getRepositoryRoot(itemProperties))
                                                          .withRepositoryUUID(InfoUtils.getRepositoryUUID(itemProperties))
                                                          .withRevision(InfoUtils.getRevision(itemProperties))
                                                          .withNodeKind(InfoUtils.getNodeKind(itemProperties))
                                                          .withSchedule(InfoUtils.getSchedule(itemProperties))
                                                          .withLastChangedRev(InfoUtils.getLastChangedRev(itemProperties))
                                                          .withLastChangedDate(InfoUtils.getLastChangedDate(itemProperties));
                    items.add(item);

                    // clear item properties
                    itemProperties.clear();
                } else {
                    // add property line to property list
                    itemProperties.add(propertyLine);
                }
            }

        } else {
            response.withErrorOutput(result.getStderr());
        }

        return response;
    }

    /**
     * Merges target with specified URL.
     *
     * @param request
     *         merge request
     * @return merge response
     * @throws IOException
     * @throws SubversionException
     */
    public CLIOutputResponse merge(final MergeRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> cliArgs = defaultArgs();

        // Command Name
        cliArgs.add("merge");

        cliArgs.add(request.getSourceURL());

        List<String> paths = new ArrayList<String>();
        paths.add(request.getTarget());

        final CommandLineResult result = runCommand(null, cliArgs, projectPath, paths);

        return DtoFactory.getInstance().createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout())
                         .withErrOutput(result.getStderr());
    }

    public CLIOutputResponse cleanup(final CleanupRequest request) throws SubversionException, IOException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> cliArgs = defaultArgs();

        // Command Name
        cliArgs.add("cleanup");

        final CommandLineResult result = runCommand(null, cliArgs, projectPath, addWorkingCopyPathIfNecessary(request.getPaths()));
        return DtoFactory.getInstance()
                         .createDto(CLIOutputResponse.class)
                         .withCommand(result.getCommandLine().toString())
                         .withOutput(result.getStdout());
    }

    public GetRevisionsResponse getRevisions(GetRevisionsRequest request) throws IOException, SubversionException {
        final File projectPath = new File(request.getProjectPath());

        final List<String> uArgs = defaultArgs();

        addOption(uArgs, "--revision", request.getRevisionRange());
        uArgs.add("log");

        final CommandLineResult result = runCommand(null, uArgs, projectPath, Arrays.asList(request.getPath()));

        final GetRevisionsResponse response = DtoFactory.getInstance().createDto(GetRevisionsResponse.class)
                                                        .withCommand(result.getCommandLine().toString())
                                                        .withOutput(result.getStdout())
                                                        .withErrOutput(result.getStderr());

        if (result.getExitCode() == 0) {
            List<String> revisions = result.getStdout().parallelStream()
                                           .filter(line -> line.split("\\|").length == 4)
                                           .map(line -> line.split("\\|")[0].trim())
                                           .collect(Collectors.toList());
            response.withRevisions(revisions);
        }

        return response;
    }
}
