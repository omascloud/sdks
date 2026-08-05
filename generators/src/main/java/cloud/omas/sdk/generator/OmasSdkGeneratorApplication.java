package cloud.omas.sdk.generator;

import cloud.omas.sdk.generator.go.OmasGoGeneratorApplication;
import cloud.omas.sdk.generator.java.OmasJavaGeneratorApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "omas-sdk-generator",
        description = "Generate Omas Cloud SDKs from the repository OpenAPI schemas.",
        mixinStandardHelpOptions = true,
        subcommands = {
                OmasJavaGeneratorApplication.class,
                OmasGoGeneratorApplication.class,
                AllGeneratorCommand.class
        })
public final class OmasSdkGeneratorApplication implements Runnable {

    @Spec
    private CommandSpec commandSpec;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OmasSdkGeneratorApplication()).execute(args);
        if (exitCode != CommandLine.ExitCode.OK) {
            System.exit(exitCode);
        }
    }

    @Override
    public void run() {
        commandSpec.commandLine().usage(commandSpec.commandLine().getOut());
    }
}
