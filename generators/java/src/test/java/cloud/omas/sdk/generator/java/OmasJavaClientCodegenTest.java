/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.generator.java;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.GlobalSettings;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

@SuppressWarnings({"rawtypes", "unchecked"})
public class OmasJavaClientCodegenTest {

    @Test
    public void testFlattensJsonBodyPropertiesIntoOperationRequest() {
        OpenAPI openAPI = openAPIWithRequestBody("payload", new ObjectSchema()
                .addProperty("message", new StringSchema().description("Message to send."))
                .required(List.of("message")));

        new OmasJavaClientCodegen().preprocessOpenAPI(openAPI);

        Schema<?> request = openAPI.getComponents().getSchemas().get("SendOperationRequest");
        assertEquals(request.getProperties().keySet(), java.util.Set.of("id", "message"));
        assertFalse(request.getProperties().containsKey("body"));
        assertTrue(request.getRequired().containsAll(List.of("id", "message")));
        assertEquals(((Schema<?>) request.getProperties().get("message")).getDescription(), "Message to send.");
    }

    @Test
    public void testRejectsParameterAndBodyPropertyNameCollision() {
        OpenAPI openAPI = openAPIWithRequestBody("id", new ObjectSchema()
                .addProperty("id", new StringSchema()));

        IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> new OmasJavaClientCodegen().preprocessOpenAPI(openAPI));

        assertEquals(exception.getMessage(),
                "Request property 'id' is defined as both a parameter and a JSON body property: send");
    }

    @Test
    public void testGeneratedModelDoesNotContainConsecutiveBlankLines() throws IOException {
        Path output = Files.createTempDirectory("omas-generator-formatting-");
        try {
            GlobalSettings.setProperty("modelTests", "false");
            GlobalSettings.setProperty("modelDocs", "false");
            OpenAPI openAPI = new OpenAPI()
                    .components(new Components().addSchemas("FormattingExample", new ObjectSchema()
                            .addProperty("first", new StringSchema())
                            .addProperty("second", new StringSchema())
                            .addProperty("third", new StringSchema())))
                    .paths(new Paths());
            OmasJavaClientCodegen codegen = new OmasJavaClientCodegen();
            codegen.setOutputDir(output.toString());
            codegen.setApiPackage("example");
            codegen.setModelPackage("example.model");
            codegen.additionalProperties().put("sourceFolder", "src/generated/java");

            new DefaultGenerator(false)
                    .opts(new ClientOptInput().config(codegen).openAPI(openAPI))
                    .generate();

            String source = Files.readString(output.resolve(
                    "src/generated/java/example/model/FormattingExample.java"));
            assertFalse(
                    source.matches("(?s).*\\n[ \\t]*\\n[ \\t]*\\n.*"),
                    "generated model contains consecutive blank lines");
        } finally {
            GlobalSettings.clearProperty("modelTests");
            GlobalSettings.clearProperty("modelDocs");
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static OpenAPI openAPIWithRequestBody(String schemaName, Schema<?> bodySchema) {
        Components components = new Components().addSchemas(schemaName, bodySchema);
        Operation operation = new Operation()
                .operationId("send")
                .addParametersItem(new PathParameter()
                        .name("id")
                        .required(true)
                        .schema(new StringSchema()))
                .requestBody(new RequestBody()
                        .required(true)
                        .content(new Content().addMediaType(
                                "application/json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + schemaName)))));
        return new OpenAPI()
                .components(components)
                .paths(new Paths().addPathItem("/items/{id}", new PathItem().post(operation)));
    }
}
