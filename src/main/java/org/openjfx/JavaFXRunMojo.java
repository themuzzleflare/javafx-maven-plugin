package org.openjfx;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.Executor;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.codehaus.plexus.languages.java.jpms.JavaModuleDescriptor;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.openjfx.model.RuntimePathOption.CLASSPATH;
import static org.openjfx.model.RuntimePathOption.MODULEPATH;

@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.RUNTIME)
@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
public class JavaFXRunMojo extends JavaFXBaseMojo {
    /**
     * <p>
     * The executable. Can be a full path or the name of the executable. In the latter case, the executable must be in
     * the PATH for the execution to work.
     * </p>
     */
    @Parameter(property = "javafx.executable", defaultValue = "java")
    private String executable;

    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("skipping execute as per configuration");
            return;
        }

        if (executable == null) {
            throw new MojoExecutionException("The parameter 'executable' is missing or invalid");
        }

        if (basedir == null) {
            throw new IllegalStateException("basedir is null. Should not be possible.");
        }

        try {
            handleWorkingDirectory();

            Map<String, String> enviro = handleSystemEnvVariables();
            CommandLine commandLine = getExecutablePath(executable, enviro, workingDirectory);

            boolean usingOldJDK = isTargetUsingJava8(commandLine);

            List<String> commandArguments = createCommandArguments(usingOldJDK);
            String[] args = commandArguments.toArray(new String[0]);
            commandLine.addArguments(args, false);
            getLog().debug("Executing command line: " + commandLine);

            Executor exec = new DefaultExecutor.Builder<>().get();
            exec.setWorkingDirectory(workingDirectory);

            try {
                int resultCode;
                if (outputFile != null) {
                    if (!outputFile.getParentFile().exists() && !outputFile.getParentFile().mkdirs()) {
                        getLog().warn("Could not create non existing parent directories for log file: " + outputFile);
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

    private List<String> createCommandArguments(boolean oldJDK) throws MojoExecutionException {
        List<String> commandArguments = new ArrayList<>();
        preparePaths(getParent(Paths.get(executable), 2));

        if (options != null) {
            options.stream()
                    .filter(Objects::nonNull)
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(this::splitComplexArgumentString)
                    .flatMap(Collection::stream)
                    .forEach(commandArguments::add);
        }
        if (!oldJDK) {
            if (runtimePathOption == MODULEPATH || modulepathElements != null && !modulepathElements.isEmpty()) {
                commandArguments.add("--module-path");
                commandArguments.add(StringUtils.join(modulepathElements.iterator(), File.pathSeparator));
                commandArguments.add("--add-modules");
                commandArguments.add(createAddModulesString(moduleDescriptor, pathElements));
            }
        }

        if (classpathElements != null && (oldJDK || !classpathElements.isEmpty())) {
            commandArguments.add("-classpath");
            String classpath = "";
            if (oldJDK || runtimePathOption == CLASSPATH) {
                classpath = project.getBuild().getOutputDirectory() + File.pathSeparator;
            }
            classpath += StringUtils.join(classpathElements.iterator(), File.pathSeparator);
            commandArguments.add(classpath);
        }

        if (mainClass != null) {
            if (moduleDescriptor != null) {
                commandArguments.add("--module");
            }
            commandArguments.add(createMainClassString(mainClass, moduleDescriptor, runtimePathOption));
        }

        if (commandlineArgs != null) {
            commandArguments.addAll(splitComplexArgumentString(commandlineArgs));
        }
        return commandArguments;
    }

    private String createAddModulesString(JavaModuleDescriptor moduleDescriptor, Map<String, JavaModuleDescriptor> pathElements) {
        if (moduleDescriptor == null) {
            return pathElements.values().stream()
                    .filter(Objects::nonNull)
                    .map(JavaModuleDescriptor::name)
                    .filter(Objects::nonNull)
                    .filter(module -> module.startsWith(JAVAFX_PREFIX) && !module.endsWith("Empty"))
                    .collect(Collectors.joining(","));
        }
        return moduleDescriptor.name();
    }

    private List<String> splitComplexArgumentString(String argumentString) {
        char[] strArr = argumentString.trim().toCharArray();

        List<String> splitedArgs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        char expectedSeparator = ' ';
        for (int i = 0; i < strArr.length; i++) {
            char item = strArr[i];

            if (item == expectedSeparator
                    || (expectedSeparator == ' ' && Pattern.matches("\\s", String.valueOf(item)))) {

                if (expectedSeparator == '"' || expectedSeparator == '\'') {
                    sb.append(item);
                    expectedSeparator = ' ';
                } else if (!sb.isEmpty()) {
                    splitedArgs.add(sb.toString());
                    sb.delete(0, sb.length());
                }
            } else {
                if (expectedSeparator == ' ' && (item == '"' || item == '\'')) {
                    expectedSeparator = item;
                }

                sb.append(item);
            }

            if (i == strArr.length - 1 && !sb.isEmpty()) {
                splitedArgs.add(sb.toString());
            }
        }

        return splitedArgs;
    }

    // for tests

    void setExecutable(String executable) {
        this.executable = executable;
    }

    void setBasedir(File basedir) {
        this.basedir = basedir;
    }

    void setCommandlineArgs(String commandlineArgs) {
        this.commandlineArgs = commandlineArgs;
    }

    List<String> splitComplexArgumentStringAdapter(String cliOptions) {
        return splitComplexArgumentString(cliOptions);
    }
}
