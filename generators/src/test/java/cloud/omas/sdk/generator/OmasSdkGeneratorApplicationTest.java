package cloud.omas.sdk.generator;

import org.testng.annotations.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class OmasSdkGeneratorApplicationTest {

    @Test
    public void testGeneratesJavaWithoutRepositorySchema() throws Exception {
        Path directory = Files.createTempDirectory("omas-generator-test-");
        try {
            Path log = directory.resolve("generator.log");
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path"),
                    OmasSdkGeneratorApplication.class.getName(), "java")
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                throw new AssertionError("Generator timed out");
            }
            assertEquals(process.exitValue(), 0, Files.readString(log));
            assertTrue(Files.isRegularFile(directory.resolve(
                    "java/metrics/src/generated/java/cloud/omas/sdk/metrics/MetricsClient.java")));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    public void testRegistersLanguageAndAllSubcommands() {
        CommandLine commandLine = new CommandLine(new OmasSdkGeneratorApplication());

        assertEquals(commandLine.getSubcommands().keySet(), Set.of("java", "go", "typescript", "all"));
        for (String name : commandLine.getSubcommands().keySet()) {
            CommandLine.Model.OptionSpec service = commandLine.getSubcommands().get(name)
                    .getCommandSpec()
                    .findOption("--service");
            assertNotNull(service, name);
            assertEquals(service.defaultValue(), "metrics", name);
            assertEquals(service.splitRegex(), ",", name);
        }
    }

    @Test
    public void testPrintsRootAndSubcommandHelp() {
        StringWriter output = new StringWriter();
        CommandLine commandLine = new CommandLine(new OmasSdkGeneratorApplication());
        commandLine.setOut(new PrintWriter(output));

        assertEquals(commandLine.execute("--help"), CommandLine.ExitCode.OK);
        assertTrue(output.toString().contains("java"), output.toString());
        assertTrue(output.toString().contains("go"), output.toString());
        assertTrue(output.toString().contains("typescript"), output.toString());
        assertTrue(output.toString().contains("all"), output.toString());

        output.getBuffer().setLength(0);
        assertEquals(commandLine.execute("go", "--help"), CommandLine.ExitCode.OK);
        assertTrue(output.toString().contains("--service"), output.toString());
    }

    @Test
    public void testRejectsUnknownCommands() {
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new OmasSdkGeneratorApplication());
        commandLine.setErr(new PrintWriter(error));

        assertEquals(commandLine.execute("ruby"), CommandLine.ExitCode.USAGE);
        assertTrue(error.toString().contains("Unmatched argument"), error.toString());
    }
}
