import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.OS;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Mojo(name = "jlink", requiresDependencyResolution = ResolutionScope.RUNTIME)
@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class JavaFXJLinkMojo extends JavaFXBaseMojo {
    private static final Pattern JLINK_VERSION_PATTERN = Pattern.compile("(1[3-9]|[2-9][0-9]|\\d{3,})");

    /**
     * Strips debug information out, equivalent to <code>-G, --strip-debug</code>,
     * default false
     */
    @Parameter(property = "javafx.stripDebug", defaultValue = "false")
    private boolean stripDebug;

    /**
     * Strip Java debug attributes out, equivalent to <code>--strip-java-debug-attributes</code>,
     * default false
     */
    @Parameter(property = "javafx.stripJavaDebugAttributes", defaultValue = "false")
    private boolean stripJavaDebugAttributes;

    /**
     * Compression level of the resources being used, equivalent to:
     * <code>-c, --compress=zip-[level]</code>. Valid values: <code>0, 1, 2, 3, 4, 5, 6, 7, 8, 9</code>,
     * default 6
     */
    @Parameter(property = "javafx.compress", defaultValue = "6")
    private Integer compress;

    /**
     * Remove the <code>includes</code> directory in the resulting runtime image,
     * equivalent to: <code>--no-header-files</code>, default false
     */
    @Parameter(property = "javafx.noHeaderFiles", defaultValue = "false")
    private boolean noHeaderFiles;

    /**
     * Remove the <code>man</code> directory in the resulting Java runtime image,
     * equivalent to: <code>--no-man-pages</code>, default false
     */
    @Parameter(property = "javafx.noManPages", defaultValue = "false")
    private boolean noManPages;

    /**
     * Add the option <code>--bind-services</code> or not, default false.
     */
    @Parameter(property = "javafx.bindServices", defaultValue = "false")
    private boolean bindServices;

    /**
     * <code>--ignore-signing-information</code>, default false
     */
    @Parameter(property = "javafx.ignoreSigningInformation", defaultValue = "false")
    private boolean ignoreSigningInformation;

    /**
     * Turn on verbose mode, equivalent to: <code>--verbose</code>, default false
     */
    @Parameter(property = "javafx.jlinkVerbose", defaultValue = "false")
    private boolean jlinkVerbose;

    /**
     * Add a launcher script, equivalent to:
     * <code>--launcher &lt;name&gt;=&lt;module&gt;[/&lt;mainclass&gt;]</code>.
     */
    @Parameter(property = "javafx.launcher")
    private String launcher;

    /**
     * The name of the folder with the resulting runtime image,
     * equivalent to <code>--output &lt;path&gt;</code>
     */
    @Parameter(property = "javafx.jlinkImageName", defaultValue = "image")
    private String jlinkImageName;

    /**
     * When set, creates a zip of the resulting runtime image.
     */
    @Parameter(property = "javafx.jlinkZipName")
    private String jlinkZipName;

    /**
     * <p>
     * The executable. Can be a full path or the name of the executable.
     * In the latter case, the executable must be in the PATH for the execution to work.
     * </p>
     */
    @Parameter(property = "javafx.jlinkExecutable", defaultValue = "jlink")
    private String jlinkExecutable;

    /**
     * Optional jmodsPath path for local builds.
     */
    @Parameter(property = "javafx.jmodsPath")
    private String jmodsPath;

    /**
     * The JAR archiver needed for archiving the environments.
     */
    @Component(role = Archiver.class, hint = "zip")
    private ZipArchiver zipArchiver;

    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info( "skipping execute as per configuration" );
            return;
        }

        if (jlinkExecutable == null) {
            throw new MojoExecutionException("The parameter 'jlinkExecutable' is missing or invalid");
        }

        if (basedir == null) {
            throw new IllegalStateException( "basedir is null. Should not be possible." );
        }

        handleWorkingDirectory();

        Map<String, String> enviro = handleSystemEnvVariables();
        CommandLine commandLine = getExecutablePath(jlinkExecutable, enviro, workingDirectory);

        if (isTargetUsingJava8(commandLine)) {
            getLog().info("Jlink not supported with Java 1.8");
            return;
        }

        if (stripJavaDebugAttributes && !isJLinkVersion13orHigher(commandLine.getExecutable())) {
            stripJavaDebugAttributes = false;
            getLog().warn("JLink parameter --strip-java-debug-attributes only supported for version 13 and higher");
            getLog().warn("The option 'stripJavaDebugAttributes' was skipped");
        }

        try {

            List<String> commandArguments = createCommandArguments();
            String[] args = commandArguments.toArray(new String[0]);
            commandLine.addArguments(args, false);
            getLog().debug("Executing command line: " + commandLine);

            Executor exec = new DefaultExecutor.Builder<>().get();
            exec.setWorkingDirectory(workingDirectory);

            try {
                int resultCode;
                if (outputFile != null) {
                    if ( !outputFile.getParentFile().exists() && !outputFile.getParentFile().mkdirs()) {
                        getLog().warn( "Could not create non existing parent directories for log file: " + outputFile );
                    }

                    try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                        resultCode = executeCommandLine(exec, commandLine, enviro, outputStream);
                    }
                } else {
                    resultCode = executeCommandLine(exec, commandLine, enviro, System.out, System.err);
                }

                if (resultCode != 0) {
                    String message = "Result of " + commandLine + " execution is: '" + resultCode + "'.";
                    getLog().error(message);
                    throw new MojoExecutionException(message);
                }

                if (launcher != null && ! launcher.isEmpty()) {
                    patchLauncherScript(launcher);

                    if (OS.isFamilyWindows()) {
                        patchLauncherScript(launcher + ".bat");
                    }
                }

                if (jlinkZipName != null && ! jlinkZipName.isEmpty()) {
                    getLog().debug("Creating zip of runtime image");
                    File createZipArchiveFromImage = createZipArchiveFromImage();
                    project.getArtifact().setFile(createZipArchiveFromImage);
                }

            } catch (ExecuteException e) {
                getLog().error("Command execution failed.", e);
                e.printStackTrace();
                throw new MojoExecutionException("Command execution failed.", e);
            } catch (IOException e) {
                getLog().error("Command execution failed.", e);
                throw new MojoExecutionException("Command execution failed.", e);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error", e);
        }
    }

    private void patchLauncherScript(String launcherFilename) throws IOException {
        Path launcherPath = Paths.get(builddir.getAbsolutePath(), jlinkImageName, "bin", launcherFilename);

        if (!Files.exists(launcherPath)) {
            getLog().debug("Launcher file not exist: " + launcherPath);
            return;
        }

        if (options != null) {
            String optionsString = options.stream()
                    .filter(Objects::nonNull)
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.joining(" "));

            // Add vm options to launcher script
            List<String> lines = Files.lines(launcherPath)
                    .map(line -> {
                        boolean unixOptionsLine = "JLINK_VM_OPTIONS=".equals(line);
                        boolean winOptionsLine = "set JLINK_VM_OPTIONS=".equals(line);

                        if (unixOptionsLine || winOptionsLine) {
                            String lineWrapper = unixOptionsLine ? "\"" : "";
                            return line + lineWrapper + optionsString + lineWrapper;
                        }
                        return line;
                    })
                    .collect(Collectors.toList());
            Files.write(launcherPath, lines);
        }

        if (commandlineArgs != null) {
            // Add options to launcher script
            List<String> lines = Files.lines(launcherPath)
                    .map(line -> {
                        if (line.endsWith("$@")) {
                            return line.replace("$@", commandlineArgs + " $@");
                        }
                        return line;
                    })
                    .collect(Collectors.toList());
            Files.write(launcherPath, lines);
        }
    }

    private List<String> createCommandArguments() throws MojoExecutionException, MojoFailureException {
        List<String> commandArguments = new ArrayList<>();
        preparePaths(getParent(Paths.get(jlinkExecutable), 2));
        if (modulepathElements != null && !modulepathElements.isEmpty()) {
            commandArguments.add(" --module-path");
            String modulePath = StringUtils.join(modulepathElements.iterator(), File.pathSeparator);
            if (jmodsPath != null && ! jmodsPath.isEmpty()) {
                getLog().debug("Including jmods from local path: " + jmodsPath);
                modulePath = jmodsPath + File.pathSeparator + modulePath;
            }
            commandArguments.add(modulePath);

            commandArguments.add(" --add-modules");
            if (moduleDescriptor != null) {
                commandArguments.add(" " + moduleDescriptor.name());
            } else {
                throw new MojoExecutionException("jlink requires a module descriptor");
            }
        }

        commandArguments.add(" --output");
        File image = new File(builddir, jlinkImageName);
        getLog().debug("image output: " + image.getAbsolutePath());
        if (image.exists()) {
            try {
                Files.walk(image.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (IOException e) {
                throw new MojoExecutionException("Image can't be removed " + image.getAbsolutePath(), e);
            }
        }
        commandArguments.add(" " + image.getAbsolutePath());

        if (stripDebug) {
            commandArguments.add(" --strip-debug");
        }
        if (stripJavaDebugAttributes) {
            commandArguments.add(" --strip-java-debug-attributes");
        }
        if (bindServices) {
            commandArguments.add(" --bind-services");
        }
        if (ignoreSigningInformation) {
            commandArguments.add(" --ignore-signing-information");
        }
        if (compress != null) {
            commandArguments.add(" --compress");
            if (compress < 0 || compress > 9) {
                throw new MojoFailureException("The given compress parameters " + compress + " is not in the valid value range from 0..9");
            }
            commandArguments.add(" " + "zip-" + compress);
        }
        if (noHeaderFiles) {
            commandArguments.add(" --no-header-files");
        }
        if (noManPages) {
            commandArguments.add(" --no-man-pages");
        }
        if (jlinkVerbose) {
            commandArguments.add(" --verbose");
        }

        if (launcher != null && ! launcher.isEmpty()) {
            commandArguments.add(" --launcher");
            String moduleMainClass;
            if (mainClass.contains("/")) {
                moduleMainClass = mainClass;
            } else {
                moduleMainClass = moduleDescriptor.name() + "/" + mainClass;
            }
            commandArguments.add(" " + launcher + "=" + moduleMainClass);
        }
        return commandArguments;
    }

    private File createZipArchiveFromImage() throws MojoExecutionException {
        File imageArchive = new File(builddir, jlinkImageName);
        zipArchiver.addDirectory(imageArchive);

        File resultArchive = new File(builddir, jlinkZipName + ".zip");
        zipArchiver.setDestFile(resultArchive);
        try {
            zipArchiver.createArchive();
        } catch (ArchiverException | IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        return resultArchive;
    }

    private boolean isJLinkVersion13orHigher(String jlinkExePath) {
        CommandLine versionCommandLine = new CommandLine(jlinkExePath)
                .addArgument("--version");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int resultCode = -1;

        try {
            resultCode = executeCommandLine(new DefaultExecutor.Builder<>().get(), versionCommandLine, null, baos, System.err);
        } catch (IOException e) {
            if (getLog().isDebugEnabled()) {
                getLog().error("Error getting JLink version", e);
            }
        }

        if (resultCode != 0) {
            getLog().error("Unable to get JLink version");
            getLog().error("Result of " + versionCommandLine + " execution is: '" + resultCode + "'");
            return false;
        }

        String versionStr = baos.toString();
        return JLINK_VERSION_PATTERN.matcher(versionStr).lookingAt();
    }

    // for tests
}
