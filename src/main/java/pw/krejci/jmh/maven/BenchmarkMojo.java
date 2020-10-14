package pw.krejci.jmh.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.compiler.CompilationFailureException;
import org.apache.maven.plugin.compiler.TestCompilerMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.repository.RepositorySystem;

/**
 * Runs the JMH benchmarks using all benchmarks files from the test sources.
 *
 * <p>
 * The properties of this goal correspond to the commandline parameters of JMH. If you want to set the number of
 * iterations, you use {@code mvn jmh:benchmark -Djmh.i=15} similarly to like you would do with JMH proper
 * {@code java -jar target/benchmark.jar -i 15}.
 *
 * <p>
 * In another words the names of the commandline parameters are the same as for JMH proper only prefixed with "jmh.".
 */
@Mojo(name = "benchmark", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.PROCESS_TEST_RESOURCES)
public class BenchmarkMojo extends TestCompilerMojo {

    private static final String PROPERTY_PREFIX = "jmh.";

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession benchmarkSession;

    @Parameter(property = PROPERTY_PREFIX + "bm")
    private String benchmarkMode;

    @Parameter(property = PROPERTY_PREFIX + "bs")
    private Integer batchSize;

    @Parameter(property = PROPERTY_PREFIX + "e")
    private List<String> exclude;

    @Parameter(property = PROPERTY_PREFIX + "f")
    private Integer fork;

    @Parameter(property = PROPERTY_PREFIX + "foe")
    private Boolean failOnError;

    @Parameter(property = PROPERTY_PREFIX + "gc")
    private Boolean forceGC;

    @Parameter(property = PROPERTY_PREFIX + "h")
    private boolean help;

    @Parameter(property = PROPERTY_PREFIX + "i")
    private Integer iterations;

    @Parameter(property = PROPERTY_PREFIX + "lprof")
    private boolean listProfilers;

    @Parameter(property = PROPERTY_PREFIX + "o")
    private File outputFile;

    @Parameter(property = PROPERTY_PREFIX + "opi")
    private Integer operationsPerInvocation;

    @Parameter(property = PROPERTY_PREFIX + "prof")
    private String profiler;

    @Parameter(property = PROPERTY_PREFIX + "r")
    private String timeOnIteration;

    @Parameter(property = PROPERTY_PREFIX + "rf")
    private String resultFormat;

    @Parameter(property = PROPERTY_PREFIX + "rff")
    private String resultsFile;

    @Parameter(property = PROPERTY_PREFIX + "si")
    private Boolean synchronizeIterations;

    @Parameter(property = PROPERTY_PREFIX + "t")
    private Integer threads;

    @Parameter(property = PROPERTY_PREFIX + "tg")
    private List<Integer> threadGroups;

    @Parameter(property = PROPERTY_PREFIX + "to")
    private String timeout;

    @Parameter(property = PROPERTY_PREFIX + "tu")
    private String timeUnit;

    @Parameter(property = PROPERTY_PREFIX + "v")
    private String verbosity;

    @Parameter(property = PROPERTY_PREFIX + "w")
    private String warmup;

    @Parameter(property = PROPERTY_PREFIX + "wbs")
    private Integer warmupBatchSize;

    @Parameter(property = PROPERTY_PREFIX + "wf")
    private Integer warmupForks;

    @Parameter(property = PROPERTY_PREFIX + "wi")
    private Integer warmupIterations;

    @Parameter(property = PROPERTY_PREFIX + "wm")
    private String warmupMode;

    @Parameter(property = PROPERTY_PREFIX + "wmb")
    private List<String> warmupBenchmarks;

    @Parameter(property = PROPERTY_PREFIX + "benchmarks")
    private List<String> benchmarks;

    @Parameter(property = PROPERTY_PREFIX + "jvmArgs")
    private List<String> jvmArgs;

    @Parameter(property = PROPERTY_PREFIX + "jvmArgsAppend")
    private List<String> jvmArgsAppend;

    @Parameter(property = PROPERTY_PREFIX + "jvmArgsPrepend")
    private List<String> jvmArgsPrepend;

    @Component
    private RepositorySystem benchmarkRepositorySystem;

    @Component
    private ArtifactHandlerManager benchmarkArtifactHandlerManager;

    @Component
    private ResolutionErrorHandler benchmarkResolutionErrorHandler;

    private List<String> jmhProcessorClasspathElements;

    @Override
    public void execute() throws MojoExecutionException, CompilationFailureException {
        super.execute();

        File benchmarkList = new File(new File(getProject().getBuild().getTestOutputDirectory(), "META-INF"),
                "BenchmarkList");
        if (!benchmarkList.exists()) {
            getLog().info("No benchmarks found, skipping.");
            return;
        }

        runBenchMarks();
    }

    @Override
    protected List<String> getClasspathElements() {
        ensureJmhProcessorResolved();
        List<String> ret = super.getClasspathElements();
        ret.addAll(jmhProcessorClasspathElements);
        return ret;
    }

    private void ensureJmhProcessorResolved() {
        if (jmhProcessorClasspathElements != null) {
            return;
        }

        String jmhVersion = getProject().getDependencies().stream()
                .filter(d -> d.getGroupId().equals("org.openjdk.jmh") && d.getArtifactId().equals("jmh-core"))
                .map(Dependency::getVersion).findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not find jmh-core in the list of dependencies."));

        Artifact artifact = new DefaultArtifact("org.openjdk.jmh", "jmh-generator-annprocess",
                VersionRange.createFromVersion(jmhVersion), Artifact.SCOPE_RUNTIME, "jar", null,
                benchmarkArtifactHandlerManager.getArtifactHandler("jar"), false);

        ArtifactResolutionRequest request = new ArtifactResolutionRequest().setArtifact(artifact).setResolveRoot(true)
                .setResolveTransitively(true).setLocalRepository(benchmarkSession.getLocalRepository())
                .setRemoteRepositories(getProject().getRemoteArtifactRepositories());

        ArtifactResolutionResult resolutionResult = benchmarkRepositorySystem.resolve(request);

        try {
            benchmarkResolutionErrorHandler.throwErrors(request, resolutionResult);
        } catch (ArtifactResolutionException e) {
            getLog().error(e.getMessage());
            throw new IllegalStateException("Failed to resolve the JMH annotation processor.");
        }

        List<String> elements = new ArrayList<>(resolutionResult.getArtifacts().size());

        List<String> classpathElements = super.getClasspathElements();
        for (Artifact resolved : resolutionResult.getArtifacts()) {
            String path = resolved.getFile().getAbsolutePath();
            if (!classpathElements.contains(path)) {
                elements.add(path);
            }
        }

        jmhProcessorClasspathElements = elements;
    }

    private void runBenchMarks() throws MojoExecutionException {
        List<String> classPath = super.getClasspathElements();
        try {
            classPath.addAll(getProject().getTestClasspathElements());
        } catch (DependencyResolutionRequiredException e) {
            // ignored, because this should not happen. We're requiring dependency resolution...
        }

        String jvm = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> command = new ArrayList<>();
        command.add(jvm);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classPath));
        command.add("org.openjdk.jmh.Main");
        if (help) {
            command.add("-h");
        }
        if (listProfilers) {
            command.add("-lprof");
        }
        addParameter(command, "-bm", benchmarkMode);
        addParameter(command, "-bs", batchSize);
        addParameter(command, "-e", exclude);
        addParameter(command, "-f", fork);
        addParameter(command, "-foe", failOnError);
        addParameter(command, "-gc", forceGC);
        addParameter(command, "-i", iterations);
        addParameter(command, "-o", outputFile);
        addParameter(command, "-opi", operationsPerInvocation);
        addParameter(command, "-prof", profiler);
        addParameter(command, "-r", timeOnIteration);
        addParameter(command, "-rf", resultFormat);
        addParameter(command, "-rff", resultsFile);
        addParameter(command, "-si", synchronizeIterations);
        addParameter(command, "-t", threads);
        addParameter(command, "-tg", threadGroups);
        addParameter(command, "-to", timeout);
        addParameter(command, "-tu", timeUnit);
        addParameter(command, "-v", verbosity);
        addParameter(command, "-w", warmup);
        addParameter(command, "-wbs", warmupBatchSize);
        addParameter(command, "-wf", warmupForks);
        addParameter(command, "-wi", warmupIterations);
        addParameter(command, "-wm", warmupMode);
        addParameter(command, "-wmb", warmupBenchmarks);
        addParameter(command, "-jvmArgs", jvmArgs);
        addParameter(command, "-jvmArgsPrepend", jvmArgsPrepend);
        addParameter(command, "-jvmArgsAppend", jvmArgsAppend);
        if (benchmarks != null) {
            command.addAll(benchmarks);
        }

        getLog().debug("Running JMH using: " + command);

        ProcessBuilder bld = new ProcessBuilder(command);
        bld.directory(getProject().getBasedir());
        bld.redirectErrorStream(true);
        try {
            getLog().info("Executing the JMH benchmarks");
            Process process = bld.start();
            try (BufferedReader rdr = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = rdr.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new MojoExecutionException("The benchmark process failed with non-zero exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException("Could not complete the benchmark: " + e.getMessage());
        }
    }

    private static void addParameter(List<String> command, String paramName, Object paramValue) {
        paramValue = cleanseValue(paramValue);
        if (paramValue != null) {
            if (paramValue instanceof List) {
                String val = ((List<?>) paramValue).stream().map(BenchmarkMojo::cleanseValue).filter(Objects::nonNull)
                        .map(Object::toString).collect(Collectors.joining(","));
                if (!val.isEmpty()) {
                    command.add(paramName);
                    command.add(val);
                }
            } else {
                command.add(paramName);
                command.add(paramValue.toString());
            }
        }
    }

    private static Object cleanseValue(Object paramValue) {
        if (paramValue == null) {
            return null;
        }

        if (paramValue instanceof String && ((String) paramValue).trim().isEmpty()) {
            return null;
        }

        return paramValue;
    }
}
