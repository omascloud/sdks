package cloud.omas.sdk.generator;

import org.testng.annotations.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class OmasSdkGeneratorApplicationTest {

    @Test
    public void testRegistersLanguageAndAllSubcommands() {
        CommandLine commandLine = new CommandLine(new OmasSdkGeneratorApplication());

        assertEquals(commandLine.getSubcommands().keySet(), Set.of("java", "go", "all"));
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

        assertEquals(commandLine.execute("typescript"), CommandLine.ExitCode.USAGE);
        assertTrue(error.toString().contains("Unmatched argument"), error.toString());
    }
}
