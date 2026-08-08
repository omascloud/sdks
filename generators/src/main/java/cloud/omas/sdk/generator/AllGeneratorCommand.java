package cloud.omas.sdk.generator;

import cloud.omas.sdk.generator.go.OmasGoGeneratorApplication;
import cloud.omas.sdk.generator.java.OmasJavaGeneratorApplication;
import cloud.omas.sdk.generator.typescript.OmasTypeScriptGeneratorApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "all",
        description = "Generate every SDK language.",
        mixinStandardHelpOptions = true)
public final class AllGeneratorCommand implements Callable<Integer> {

    @Option(
            names = {"-s", "--service"},
            defaultValue = "metrics",
            split = ",",
            paramLabel = "<name>",
            description = "Service to generate; may be repeated or comma-separated.")
    private List<String> services;

    @Override
    public Integer call() {
        OmasJavaGeneratorApplication.generateServices(services);
        OmasGoGeneratorApplication.generateServices(services);
        OmasTypeScriptGeneratorApplication.generateServices(services);
        return 0;
    }
}
