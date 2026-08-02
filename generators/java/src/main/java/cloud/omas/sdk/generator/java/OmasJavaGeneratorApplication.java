/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.generator.java;

import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

import java.nio.file.Path;
import java.util.List;

public final class OmasJavaGeneratorApplication {

    private static final List<String> SERVICES = List.of("metrics");

    private OmasJavaGeneratorApplication() {
    }

    public static void main(String[] args) {
        SERVICES.forEach(OmasJavaGeneratorApplication::generate);
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
