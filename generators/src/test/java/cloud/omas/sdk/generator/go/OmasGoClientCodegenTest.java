/**
 * Copyright (c) 2026 Omas Cloud
 *
 * SPDX-License-Identifier: MIT
 */

package cloud.omas.sdk.generator.go;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.CookieParameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.GlobalSettings;
import org.testng.annotations.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

@SuppressWarnings({"rawtypes", "unchecked"})
public class OmasGoClientCodegenTest {

    @Test
    public void testFlattensJsonBodyPropertiesIntoOperationRequest() {
        OpenAPI openAPI = openAPIWithRequestBody("payload", new ObjectSchema()
                .addProperty("message", new StringSchema().description("Message to send."))
                .required(List.of("message")));
        Operation operation = openAPI.getPaths().get("/items/{id}").getPost();
        operation.addParametersItem(new QueryParameter().name("limit").schema(new Schema<>().type("integer")));
        OmasGoClientCodegen codegen = new OmasGoClientCodegen();

        codegen.preprocessOpenAPI(openAPI);

        Schema<?> request = openAPI.getComponents().getSchemas().get("SendOperationRequest");
        assertEquals(request.getProperties().keySet(), Set.of("id", "limit", "message"));
        assertFalse(request.getProperties().containsKey("body"));
        assertTrue(request.getRequired().containsAll(List.of("id", "message")));
        assertEquals(((Schema<?>) request.getProperties().get("message")).getDescription(), "Message to send.");
        assertEquals(((Schema<?>) request.getProperties().get("id"))
                .getExtensions().get("x-sdk-json-ignore"), true);
        assertEquals(((Schema<?>) request.getProperties().get("limit"))
                .getExtensions().get("x-sdk-json-ignore"), true);
        assertTrue(((Schema<?>) request.getProperties().get("message")).getExtensions() == null
                || !((Schema<?>) request.getProperties().get("message")).getExtensions()
                        .containsKey("x-sdk-json-ignore"));

        CodegenModel model = codegen.fromModel("SendOperationRequest", request);
        assertEquals(model.vars.stream()
                .filter(property -> Boolean.TRUE.equals(property.vendorExtensions.get("x-sdk-json-ignore")))
                .map(property -> property.baseName)
                .collect(java.util.stream.Collectors.toSet()), Set.of("id", "limit"));
    }

    @Test
    public void testRejectsParameterAndBodyPropertyNameCollision() {
        OpenAPI openAPI = openAPIWithRequestBody("payload", new ObjectSchema()
                .addProperty("id", new StringSchema()));

        IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> new OmasGoClientCodegen().preprocessOpenAPI(openAPI));

        assertEquals(exception.getMessage(),
                "Request property 'id' is defined as both a parameter and a JSON body property: send");
    }

    @Test
    public void testRejectsCookieParameters() {
        OpenAPI openAPI = openAPIWithRequestBody("payload", new ObjectSchema());
        openAPI.getPaths().get("/items/{id}").getPost()
                .addParametersItem(new CookieParameter().name("session").schema(new StringSchema()));

        IllegalArgumentException exception = expectThrows(
                IllegalArgumentException.class,
                () -> new OmasGoClientCodegen().preprocessOpenAPI(openAPI));

        assertEquals(exception.getMessage(), "Cookie parameters are not supported: send");
    }

    @Test
    public void testCollectsSortedServiceErrorMetadata() {
        ObjectSchema details = new ObjectSchema();
        details.addProperty("resourceType", new StringSchema());
        ObjectSchema notFound = exception("RESOURCE_NOT_FOUND");
        notFound.addProperty("extraData", nullableReference(
                "#/components/schemas/ResourceNotFoundExceptionDetails"));
        ObjectSchema accessDenied = exception("ACCESS_DENIED");
        OpenAPI openAPI = new OpenAPI()
                .components(new Components()
                        .addSchemas("ResourceNotFoundExceptionDetails", details)
                        .addSchemas("ResourceNotFoundException", notFound)
                        .addSchemas("AccessDeniedException", accessDenied))
                .paths(new Paths());
        OmasGoClientCodegen codegen = new OmasGoClientCodegen();

        codegen.preprocessOpenAPI(openAPI);

        List<Map<String, Object>> errors = (List<Map<String, Object>>)
                codegen.additionalProperties().get("serviceErrors");
        assertNotNull(errors);
        assertEquals(errors.size(), 2);
        assertEquals(errors.get(0).get("errorCode"), "ACCESS_DENIED");
        assertEquals(errors.get(0).get("type"), "AccessDeniedError");
        assertEquals(errors.get(0).get("hasDetails"), false);
        assertEquals(errors.get(1).get("errorCode"), "RESOURCE_NOT_FOUND");
        assertEquals(errors.get(1).get("type"), "ResourceNotFoundError");
        assertEquals(errors.get(1).get("detailsType"), "ResourceNotFoundExceptionDetails");
        assertEquals(errors.get(1).get("hasDetails"), true);
    }

    @Test
    public void testGeneratedCleanupOnlyDeletesMarkedFiles() throws IOException {
        Path output = Files.createTempDirectory("omas-go-cleanup-");
        try {
            Path generated = output.resolve("model.go");
            Path handwritten = output.resolve("manual.go");
            Path test = output.resolve("client_test.go");
            Files.writeString(generated,
                    "// Code generated by the Omas Go SDK generator. DO NOT EDIT.\npackage metrics\n");
            Files.writeString(handwritten, "package metrics\n");
            Files.writeString(test, "package metrics\n");

            OmasGoGeneratorApplication.removeGeneratedFiles(output);

            assertFalse(Files.exists(generated));
            assertTrue(Files.exists(handwritten));
            assertTrue(Files.exists(test));
        } finally {
            try (Stream<Path> paths = Files.walk(output)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void testGeneratedGoFilesAreFormatted() throws IOException {
        Path output = Files.createTempDirectory("omas-go-format-");
        try {
            Path source = output.resolve("client.go");
            Files.writeString(source, "package metrics\nfunc example(){println(\"value\")}\n");

            OmasGoGeneratorApplication.formatGeneratedFiles(output);

            assertEquals(Files.readString(source),
                    "package metrics\n\nfunc example() { println(\"value\") }\n");
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void testGeneratesCompactValidatedOperationRequest() throws IOException {
        ObjectSchema payload = new ObjectSchema();
        payload.addProperty("message", new StringSchema()
                .minLength(2)
                .maxLength(20)
                .pattern("^[a-z]+$"));
        payload.addProperty("labels", new ArraySchema()
                .items(new StringSchema())
                .minItems(1)
                .maxItems(3)
                .uniqueItems(true));
        payload.addProperty("alias", new StringSchema().pattern("^[a-z]+$"));
        payload.addProperty("lastSeen", new IntegerSchema().format("int64").nullable(true));
        payload.setRequired(List.of("message", "labels", "lastSeen"));
        OpenAPI openAPI = openAPIWithRequestBody("payload", payload);
        openAPI.getPaths().get("/items/{id}").getPost().addParametersItem(
                new QueryParameter().name("limit").schema(new IntegerSchema()
                        .format("int32")
                        .minimum(BigDecimal.ONE)
                        .maximum(BigDecimal.TEN)));
        Path output = Files.createTempDirectory("omas-go-model-");
        try {
            generate(openAPI, output);
            String source = Files.readString(findFile(output, "model_send_operation_request.go"));

            assertTrue(source.contains("type SendOperationRequest struct {"), source);
            assertTrue(source.contains("ID string `json:\"-\"`"), source);
            assertTrue(source.contains("Limit *int32 `json:\"-\"`"), source);
            assertTrue(source.contains("Message string `json:\"message\"`"), source);
            assertTrue(source.contains("Labels []string `json:\"labels\"`"), source);
            assertTrue(source.contains("LastSeen *int64 `json:\"lastSeen\"`"), source);
            assertFalse(source.contains("NullableInt64"), source);
            assertTrue(source.contains("if v.Alias != nil {"), source);
            assertFalse(source.contains("&& matched, err :="), source);
            assertTrue(source.contains("func (v SendOperationRequest) Validate() error"), source);
            assertFalse(source.contains("GetMessage"), source);
            assertFalse(source.contains("SetMessage"), source);
            assertFalse(source.contains("NullableSendOperationRequest"), source);
            assertFalse(source.contains("MappedNullable"), source);
            assertFalse(source.contains("ToMap"), source);
            assertFalse(source.contains("Body "), source);
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void testGeneratesIdiomaticClientAndTypedErrors() throws IOException {
        ObjectSchema payload = new ObjectSchema();
        payload.addProperty("message", new StringSchema());
        payload.setRequired(List.of("message"));
        OpenAPI openAPI = openAPIWithRequestBody("payload", payload)
                .servers(List.of(new Server().url("https://api.example.test/")));
        ObjectSchema details = new ObjectSchema();
        details.addProperty("resourceType", new StringSchema());
        ObjectSchema notFound = exception("RESOURCE_NOT_FOUND");
        notFound.addProperty("extraData", nullableReference(
                "#/components/schemas/ResourceNotFoundExceptionDetails"));
        openAPI.getComponents()
                .addSchemas("ResourceNotFoundExceptionDetails", details)
                .addSchemas("ResourceNotFoundException", notFound)
                .addSchemas("AccessDeniedException", exception("ACCESS_DENIED"));
        Path output = Files.createTempDirectory("omas-go-client-");
        try {
            generate(openAPI, output);
            String client = Files.readString(findFile(output, "metrics_client.go"));
            String apiErrors = Files.readString(findFile(output, "api_errors.go"));

            assertTrue(client.contains("type Client struct"), client);
            assertTrue(client.contains(
                    "func NewClient(authProvider core.AuthProvider, options ...core.ClientOption) (*Client, error)"),
                    client);
            assertTrue(client.contains("options = append(options, core.WithAPIErrorDecoder(decodeAPIError))"),
                    client);
            assertTrue(client.indexOf("core.WithAPIErrorDecoder(decodeAPIError)")
                    < client.indexOf("core.NewClient(\"metrics\""), client);
            assertTrue(client.contains("func (c *Client) Close() error"), client);
            assertTrue(client.contains("if err := request.Validate(); err != nil"), client);
            assertTrue(client.contains(
                    "c.client.Do(ctx, \"send\", http.MethodPost, path, query, headers, request, nil)"),
                    client);
            assertFalse(client.contains("request.Body"), client);
            assertFalse(client.contains("Builder"), client);

            assertTrue(apiErrors.contains("type ResourceNotFoundError struct"), apiErrors);
            assertTrue(apiErrors.contains("*core.APIError"), apiErrors);
            assertTrue(apiErrors.contains("Details *ResourceNotFoundExceptionDetails"), apiErrors);
            assertTrue(apiErrors.contains("func (e *ResourceNotFoundError) Unwrap() error"), apiErrors);
            assertTrue(apiErrors.contains("case \"RESOURCE_NOT_FOUND\":"), apiErrors);
            assertTrue(apiErrors.contains("type AccessDeniedError struct"), apiErrors);
            assertTrue(apiErrors.contains("default:"), apiErrors);
            assertTrue(apiErrors.contains("return apiError"), apiErrors);
        } finally {
            deleteTree(output);
        }
    }

    private static ObjectSchema exception(String errorCode) {
        ObjectSchema exception = new ObjectSchema();
        exception.addProperty("error", new StringSchema());
        exception.addProperty("errorCode", new StringSchema()._enum(List.of(errorCode)));
        return exception;
    }

    private static Schema<Object> nullableReference(String reference) {
        Schema<Object> schema = new Schema<>();
        schema.setNullable(true);
        schema.addAllOfItem(new Schema<>().$ref(reference));
        return schema;
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

    private static void generate(OpenAPI openAPI, Path output) {
        GlobalSettings.setProperty("apiTests", "false");
        GlobalSettings.setProperty("apiDocs", "false");
        GlobalSettings.setProperty("modelTests", "false");
        GlobalSettings.setProperty("modelDocs", "false");
        GlobalSettings.setProperty("supportingFiles", "");
        GlobalSettings.setProperty("models", "");
        GlobalSettings.setProperty("apis", "");
        OmasGoClientCodegen codegen = new OmasGoClientCodegen();
        codegen.setOutputDir(output.toString());
        codegen.setApiPackage("metrics");
        codegen.setModelPackage("metrics");
        codegen.additionalProperties().put("packageName", "metrics");
        codegen.additionalProperties().put("serviceName", "metrics");
        codegen.additionalProperties().put("isGoSubmodule", true);
        codegen.additionalProperties().put("sourceFolder", "");
        try {
            new DefaultGenerator(false)
                    .opts(new ClientOptInput().config(codegen).openAPI(openAPI))
                    .generate();
        } finally {
            GlobalSettings.clearProperty("apiTests");
            GlobalSettings.clearProperty("apiDocs");
            GlobalSettings.clearProperty("modelTests");
            GlobalSettings.clearProperty("modelDocs");
            GlobalSettings.clearProperty("supportingFiles");
            GlobalSettings.clearProperty("models");
            GlobalSettings.clearProperty("apis");
        }
    }

    private static Path findFile(Path root, String filename) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equals(filename))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Generated file not found: " + filename));
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
