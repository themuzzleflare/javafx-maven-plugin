package org.openjfx;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.OS;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.StringOutputStream;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.junit.Assert;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JavaFXRunMojoTestCase extends AbstractMojoTestCase {
    private static final File LOCAL_REPO = new File("src/test/repository");
    private static final String SOME_EXECUTABLE = UUID.randomUUID().toString();

    @Mock
    private MavenSession session;

    private MockJavaFXRunMojo mojo;

    public void setUp() throws Exception {
        super.setUp();
        mojo = new MockJavaFXRunMojo();
        mojo.executeResult = 0;
        mojo.setExecutable(SOME_EXECUTABLE);
//        mojo.setOptions(Arrays.asList("--version"));
        mojo.setCommandlineArgs("--version");
        mojo.setBasedir(File.createTempFile("mvn-temp", "txt").getParentFile());
    }

    public void testRunOK() throws MojoExecutionException {
        mojo.execute();
        checkMojo(SOME_EXECUTABLE + " --version");
    }

    public void testSimpleRun() throws Exception {
        final File testPom = getTestFile("src/test/resources/unit/javafxrun-basic-test/pom.xml");
        JavaFXRunMojo mojo = getJavaFXRunMojo(testPom);
        String output = execute(mojo);
        assertEquals("JavaFXRun0", output.trim());
    }

    public void testApplicationRun() throws Exception {
        final File testPom = getTestFile("src/test/resources/unit/javafxrun-app-test/pom.xml");
        JavaFXRunMojo mojo = getJavaFXRunMojo(testPom);
        String output = execute(mojo);
        assertEquals("JavaFXRun1", output.trim());
    }

    public void testClasspathLauncherRun() throws Exception {
        final File testPom = getTestFile("src/test/resources/unit/javafxrun-classpath-test/classpath-launcher.xml");
        JavaFXRunMojo mojo = getJavaFXRunMojo(testPom);
        String output = execute(mojo);
        assertEquals("JavaFXRun1", output.trim());
    }

    public void testClasspathNoLauncherRun() throws Exception {
        final File testPom = getTestFile("src/test/resources/unit/javafxrun-classpath-test/classpath-no-launcher.xml");
        JavaFXRunMojo mojo = getJavaFXRunMojo(testPom);
        Exception e = null;
        try {
            execute(mojo);
        } catch (Exception e1) {
            e = e1;
        }
        assertTrue((e instanceof MojoExecutionException));
    }

    protected JavaFXRunMojo getJavaFXRunMojo(File testPom) throws Exception {
        JavaFXRunMojo mojo = (JavaFXRunMojo) lookupMojo("run", testPom);
        assertNotNull(mojo);

        setUpProject(testPom, mojo);
        MavenProject project = (MavenProject) getVariableValueFromObject(mojo, "project");
        assertNotNull(project);

        if (!project.getDependencies().isEmpty()) {
            final MavenArtifactResolver resolver = new MavenArtifactResolver(project.getRepositories());
            Set<Artifact> artifacts = project.getDependencies().stream()
                    .map(d -> new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(), d.getType(), d.getVersion()))
                    .flatMap(a -> resolver.resolve(a).stream())
                    .collect(Collectors.toSet());
            project.setArtifacts(artifacts);
        }
        setVariableValueToObject(mojo, "compilePath", project.getCompileClasspathElements());
        setVariableValueToObject(mojo, "session", session);
        setVariableValueToObject(mojo, "executable", "java");
        setVariableValueToObject(mojo, "basedir", new File(getBasedir(), testPom.getParent()));

        return mojo;
    }

    private String execute(AbstractMojo mojo) throws MojoFailureException, MojoExecutionException, InterruptedException {
        PrintStream out = System.out;
        StringOutputStream stringOutputStream = new StringOutputStream();
        System.setOut(new PrintStream(stringOutputStream));
        mojo.setLog(new DefaultLog(new ConsoleLogger(Logger.LEVEL_ERROR, "javafx:run")));

        try {
            mojo.execute();
        } finally {
            Thread.sleep(300);
            System.setOut(out);
        }

        return stringOutputStream.toString();
    }

    private void setUpProject(File pomFile, AbstractMojo mojo) throws Exception {
        super.setUp();

        MockitoAnnotations.openMocks(this);

        ProjectBuildingRequest buildingRequest = mock(ProjectBuildingRequest.class);
        buildingRequest.setResolveDependencies(true);
        when(session.getProjectBuildingRequest()).thenReturn(buildingRequest);
        DefaultRepositorySystemSession repositorySession = MavenRepositorySystemUtils.newSession();
        repositorySession.setLocalRepositoryManager(new SimpleLocalRepositoryManagerFactory()
                .newInstance(repositorySession, new LocalRepository(RepositorySystem.defaultUserLocalRepository)));
        when(buildingRequest.getRepositorySession()).thenReturn(repositorySession);

        ProjectBuilder builder = lookup(ProjectBuilder.class);
        ProjectBuildingResult build = builder.build(pomFile, buildingRequest);
        MavenProject project = build.getProject();

        project.getBuild().setOutputDirectory(new File("target/test-classes").getAbsolutePath());
        setVariableValueToObject(mojo, "project", project);
    }

    private void checkMojo(String expectedCommandLine) {
        assertEquals(1, mojo.getAmountExecutedCommandLines());
        CommandLine commandline = mojo.getExecutedCommandline(0);
        // do NOT depend on Commandline toString()
        assertEquals(expectedCommandLine, getCommandLineAsString(commandline));
    }

    private String getCommandLineAsString(CommandLine commandline) {
        // for the sake of the test comparisons, cut out the eventual
        // cmd /c *.bat conversion
        StringBuilder result = new StringBuilder(commandline.getExecutable());
        boolean isCmd = false;
        if (OS.isFamilyWindows() && result.toString().equals("cmd")) {
            result = new StringBuilder();
            isCmd = true;
        }
        String[] arguments = commandline.getArguments();
        for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i];
            if (isCmd && i == 0 && "/c".equals(arg)) {
                continue;
            }
            if (isCmd && i == 1 && arg.endsWith(".bat")) {
                arg = arg.substring(0, arg.length() - ".bat".length());
            }
            result.append((result.isEmpty()) ? "" : " ").append(arg);
        }
        return result.toString();
    }

    public void testSplitComplexArgumentString() {
        String option = """
                param1 \
                param2  \s
                   \
                param3
                param4="/path/to/my file.log"   \
                'var"foo   var"foo' \
                'var"foo'   \
                'var"foo' \
                "foo'var foo'var" \
                "foo'var" \
                "foo'var\"""";

        String expected = "START," +
                "param1," +
                "param2," +
                "param3," +
                "param4=\"/path/to/my file.log\"," +
                "'var\"foo   var\"foo'," +
                "'var\"foo'," +
                "'var\"foo'," +
                "\"foo'var foo'var\"," +
                "\"foo'var\"," +
                "\"foo'var\"";

        String splitedOption = new JavaFXRunMojo().splitComplexArgumentStringAdapter(option)
                .stream().reduce("START", (s1, s2) -> s1 + "," + s2);

        Assert.assertEquals(expected, splitedOption);
    }

    private static class MockJavaFXRunMojo extends JavaFXRunMojo {
        public List<CommandLine> commandLines = new ArrayList<>();
        public String failureMsg;
        public int executeResult;

        @Override
        protected int executeCommandLine(Executor exec, CommandLine commandLine, Map enviro, OutputStream out,
                                         OutputStream err) throws IOException {
            commandLines.add(commandLine);
            if (failureMsg != null) {
                throw new ExecuteException(failureMsg, executeResult);
            }
            return executeResult;
        }

        int getAmountExecutedCommandLines() {
            return commandLines.size();
        }

        CommandLine getExecutedCommandline(int index) {
            return commandLines.get(index);
        }
    }
}
