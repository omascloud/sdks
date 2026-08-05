/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.generator.java;

import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "java",
        description = "Generate the Java SDK.",
        mixinStandardHelpOptions = true)
public final class OmasJavaGeneratorApplication implements Callable<Integer> {

    @Option(
            names = {"-s", "--service"},
            defaultValue = "metrics",
            split = ",",
            paramLabel = "<name>",
            description = "Service to generate; may be repeated or comma-separated.")
    private List<String> services;

    @Override
    public Integer call() {
        generateServices(services);
        return 0;
    }

    public static void generateServices(List<String> serviceNames) {
        serviceNames.forEach(OmasJavaGeneratorApplication::generate);
    }

    private static void generate(String serviceName) {
        String basePackage = "cloud.omas.sdk." + serviceName;
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName("omas-java")
                .setInputSpec(Path.of("schema", serviceName + ".yaml").toString())
                .setOutputDir(Path.of("java", serviceName).toString())
                .setApiPackage(basePackage)
                .setModelPackage(basePackage + ".model")
                .addGlobalProperty("apis", "")
                .addGlobalProperty("models", "")
                .addGlobalProperty("apiTests", "false")
                .addGlobalProperty("apiDocs", "false")
                .addGlobalProperty("modelTests", "false")
                .addGlobalProperty("modelDocs", "false")
                .addGlobalProperty("supportingFiles", "false")
                .addAdditionalProperty("dateLibrary", "java8")
                .addAdditionalProperty("generateBuilders", "true")
                .addAdditionalProperty("hideGenerationTimestamp", "true")
                .addAdditionalProperty("openApiNullable", "false")
                .addAdditionalProperty("serializationLibrary", "jackson")
                .addAdditionalProperty("serviceName", serviceName)
                .addAdditionalProperty("sourceFolder", "src/generated/java")
                .addAdditionalProperty("useJakartaEe", "true");

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }
}
