package cloud.omas.sdk.generator.typescript;

import cloud.omas.sdk.generator.common.ApiErrorCatalog;
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
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.testng.annotations.Test;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.GlobalSettings;
import org.openapitools.codegen.utils.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@SuppressWarnings({"rawtypes", "unchecked"})
public class OmasTypeScriptClientCodegenTest {

    @Test
    public void testGeneratesStructuralModelsAndOperationValidation() throws IOException {
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
        payload.addProperty("alias", new StringSchema());
        payload.addProperty("lastSeen", new IntegerSchema().format("int64").nullable(true));
        payload.addProperty("status", new StringSchema()._enum(List.of("active", "disabled")));
        payload.setRequired(List.of("message", "labels", "lastSeen", "status"));
        OpenAPI openAPI = openAPIWithRequestBody(payload);
        openAPI.getPaths().get("/items/{id}").getPost().addParametersItem(
                new QueryParameter().name("limit").schema(new IntegerSchema()
                        .format("int32")
                        .minimum(BigDecimal.ONE)
                        .maximum(BigDecimal.TEN)));
        openAPI.getPaths().get("/items/{id}").getPost()
                .addParametersItem(new QueryParameter()
                        .name("tag")
                        .schema(new ArraySchema().items(new StringSchema())))
                .addParametersItem(new HeaderParameter()
                        .name("X-Trace-Id")
                        .schema(new StringSchema()));
        Path output = Files.createTempDirectory("omas-typescript-model-");
        try {
            generate(openAPI, output);
            String request = Files.readString(findFile(output, "SendOperationRequest.ts"));
            String validation = Files.readString(findFile(output, "validation.ts"));
            String client = Files.readString(findFile(output, "metrics-client.ts"));
            String errors = Files.readString(findFile(output, "api-errors.ts"));
            String metadata = Files.readString(findFile(output, "metadata.ts"));
            String index = Files.readString(findFile(output, "index.ts"));

            assertTrue(request.contains("export interface SendOperationRequest"), request);
            assertTrue(request.contains("readonly id: string;"), request);
            assertTrue(request.contains("readonly limit?: number;"), request);
            assertTrue(request.contains("readonly message: string;"), request);
            assertTrue(request.contains("readonly labels: ReadonlyArray<string>;"), request);
            assertTrue(request.contains("readonly alias?: string;"), request);
            assertTrue(request.contains("readonly lastSeen: number | null;"), request);
            assertTrue(request.contains("export type SendOperationRequestStatus ="), request);
            assertTrue(request.contains("| 'active'"), request);
            assertTrue(request.contains("| 'disabled';"), request);
            assertTrue(validation.contains(
                    "export function validateSendRequest(request: SendOperationRequest): void"),
                    validation);
            assertTrue(validation.contains("request.message.trim().length === 0"), validation);
            assertTrue(validation.contains("request.limit < 1"), validation);
            assertTrue(validation.contains("request.limit > 10"), validation);
            assertTrue(validation.contains("request.message.length < 2"), validation);
            assertTrue(validation.contains("request.message.length > 20"), validation);
            assertTrue(validation.contains("/^[a-z]+$/.test(request.message)"), validation);
            assertTrue(validation.contains("request.labels.length < 1"), validation);
            assertTrue(validation.contains("request.labels.length > 3"), validation);
            assertTrue(validation.contains("stableKey"), validation);
            assertFalse(validation.contains("validatePayload"), validation);
            assertTrue(client.contains("export class MetricsClient"), client);
            assertTrue(client.contains("\"https://api.example.test/\""), client);
            assertTrue(client.contains("decodeMetricsApiError"), client);
            assertTrue(client.contains("async send("), client);
            assertTrue(client.contains("path: `items/${encodeURIComponent(request.id)}`"), client);
            assertTrue(client.contains("validateSendRequest(request)"), client);
            assertTrue(client.contains("for (const value of request.tag)"), client);
            assertTrue(client.contains("query.push([\"tag\", String(value)])"), client);
            assertTrue(client.contains("headers.set(\"X-Trace-Id\""), client);
            assertTrue(client.contains("body[\"message\"] = request.message"), client);
            assertFalse(client.contains("body[\"id\"]"), client);
            assertTrue(client.contains("Promise<SendResponse>"), client);
            assertTrue(errors.contains("class ResourceNotFoundError extends ApiError<"), errors);
            assertTrue(errors.contains("case \"RESOURCE_NOT_FOUND\""), errors);
            assertTrue(errors.contains("return new ResourceNotFoundError(error)"), errors);
            assertTrue(metadata.contains("export const contractDigest"), metadata);
            assertTrue(index.contains("export * from \"./metrics-client.js\""), index);
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void testCoversEveryMetricsOperationModelAndKnownError() throws IOException {
        Path contractPath = Path.of("..", "schema", "metrics.yaml")
                .toAbsolutePath()
                .normalize();
        OpenAPI openAPI = new OpenAPIV3Parser().read(contractPath.toString());
        assertNotNull(openAPI);
        Set<String> operationIds = new TreeSet<>();
        openAPI.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(
                operation -> operationIds.add(operation.getOperationId())));
        Set<String> errorCodes = new TreeSet<>();
        ApiErrorCatalog.discover(openAPI).forEach(error -> errorCodes.add(error.errorCode()));
        Path output = Files.createTempDirectory("omas-typescript-metrics-coverage-");
        try {
            generate(openAPI, output);
            String client = Files.readString(findFile(output, "metrics-client.ts"));
            String errors = Files.readString(findFile(output, "api-errors.ts"));

            assertEquals(generatedNames(client, "async "), operationIds);
            assertEquals(operationIds.size(), 24);
            assertEquals(generatedNames(errors, "case \""), errorCodes);

            Set<String> expectedModels = new TreeSet<>();
            openAPI.getComponents().getSchemas().forEach((name, schema) -> {
                if (isGeneratedSchema(schema)) {
                    expectedModels.add(StringUtils.camelize(name));
                }
            });
            Set<String> generatedModels = new TreeSet<>();
            try (Stream<Path> paths = Files.list(output.resolve("models"))) {
                paths.filter(path -> path.getFileName().toString().endsWith(".ts"))
                        .map(path -> path.getFileName().toString().replaceFirst("\\.ts$", ""))
                        .filter(name -> !name.equals("index"))
                        .forEach(generatedModels::add);
            }
            assertEquals(generatedModels, expectedModels);
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void testGeneratedCleanupOnlyDeletesMarkedFiles() throws IOException {
        Path output = Files.createTempDirectory("omas-typescript-cleanup-");
        try {
            Path generated = output.resolve("model.ts");
            Path handwritten = output.resolve("index.ts");
            Path test = output.resolve("client.test.ts");
            Files.writeString(generated,
                    "// Code generated by the Omas TypeScript SDK generator. DO NOT EDIT.\n");
            Files.writeString(handwritten, "export {};\n");
            Files.writeString(test, "export {};\n");

            OmasTypeScriptGeneratorApplication.removeGeneratedFiles(output);

            assertFalse(Files.exists(generated));
            assertEquals(Files.readString(handwritten), "export {};\n");
            assertEquals(Files.readString(test), "export {};\n");
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void testCalculatesLowercaseContractDigestFromExactBytes() throws IOException {
        Path schema = Files.createTempFile("omas-typescript-schema-", ".yaml");
        try {
            Files.writeString(schema, "abc");

            assertEquals(
                    OmasTypeScriptGeneratorApplication.contractDigest(schema),
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        } finally {
            Files.deleteIfExists(schema);
        }
    }

    @Test
    public void testFormatsGeneratedTypeScriptFiles() throws IOException {
        Path output = Files.createTempDirectory("omas-typescript-format-");
        try {
            Path source = output.resolve("Example.ts");
            Path second = output.resolve("Second.ts");
            Files.writeString(source, "export interface Example{value:string}\n");
            Files.writeString(second, "export type Second=string\n");

            OmasTypeScriptGeneratorApplication.formatGeneratedFiles(output);

            assertEquals(
                    Files.readString(source),
                    "export interface Example {\n\tvalue: string;\n}\n");
            assertEquals(Files.readString(second), "export type Second = string;\n");
        } finally {
            deleteTree(output);
        }
    }

    @Test
    public void testFlattensRequestAndRecordsWireLocations() {
        ObjectSchema payload = new ObjectSchema();
        payload.addProperty("message", new StringSchema().description("Message to send."));
        payload.setRequired(List.of("message"));
        Operation operation = new Operation()
                .operationId("send")
                .addParametersItem(new PathParameter()
                        .name("id")
                        .required(true)
                        .schema(new StringSchema()))
                .addParametersItem(new QueryParameter()
                        .name("limit")
                        .schema(new StringSchema()))
                .requestBody(new RequestBody()
                        .content(new Content().addMediaType(
                                "application/json",
                                new MediaType().schema(payload))));
        OpenAPI openAPI = new OpenAPI()
                .components(new Components())
                .paths(new Paths().addPathItem(
                        "/items/{id}",
                        new PathItem().post(operation)));
        OmasTypeScriptClientCodegen codegen = new OmasTypeScriptClientCodegen();

        codegen.preprocessOpenAPI(openAPI);

        ObjectSchema request = (ObjectSchema) openAPI.getComponents()
                .getSchemas()
                .get("SendOperationRequest");
        assertNotNull(request);
        assertEquals(request.getProperties().keySet(), Set.of("id", "limit", "message"));
        assertEquals(property(request, "id").getExtensions().get("x-sdk-location"), "path");
        assertEquals(property(request, "limit").getExtensions().get("x-sdk-location"), "query");
        assertEquals(property(request, "message").getExtensions().get("x-sdk-location"), "body");
        assertEquals(operation.getExtensions().get("x-sdk-request-class"), "SendOperationRequest");
    }

    @Test
    public void testCollectsSortedServiceErrorMetadata() {
        ObjectSchema details = new ObjectSchema();
        details.addProperty("resourceType", new StringSchema());
        ObjectSchema notFound = exception("RESOURCE_NOT_FOUND");
        notFound.addProperty(
                "extraData",
                new ObjectSchema().$ref("#/components/schemas/ResourceNotFoundExceptionDetails"));
        OpenAPI openAPI = new OpenAPI()
                .components(new Components()
                        .addSchemas("ResourceNotFoundExceptionDetails", details)
                        .addSchemas("ResourceNotFoundException", notFound)
                        .addSchemas("AccessDeniedException", exception("ACCESS_DENIED")))
                .paths(new Paths());
        OmasTypeScriptClientCodegen codegen = new OmasTypeScriptClientCodegen();

        codegen.preprocessOpenAPI(openAPI);

        List<Map<String, Object>> errors = (List<Map<String, Object>>)
                codegen.additionalProperties().get("serviceErrors");
        assertNotNull(errors);
        assertEquals(errors.size(), 2);
        assertEquals(errors.get(0).get("errorCode"), "ACCESS_DENIED");
        assertEquals(errors.get(0).get("type"), "AccessDeniedError");
        assertFalse((Boolean) errors.get(0).get("hasDetails"));
        assertEquals(errors.get(1).get("errorCode"), "RESOURCE_NOT_FOUND");
        assertEquals(errors.get(1).get("type"), "ResourceNotFoundError");
        assertEquals(errors.get(1).get("detailsType"), "ResourceNotFoundExceptionDetails");
    }

    private static ObjectSchema exception(String errorCode) {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("errorCode", new StringSchema()._enum(List.of(errorCode)));
        schema.addProperty("error", new StringSchema());
        schema.setRequired(List.of("errorCode", "error"));
        return schema;
    }

    private static Set<String> generatedNames(String source, String prefix) {
        Set<String> names = new TreeSet<>();
        for (String line : source.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            String rest = trimmed.substring(prefix.length());
            int end = rest.indexOf(prefix.equals("async ") ? '(' : '"');
            if (end >= 0) {
                names.add(rest.substring(0, end));
            }
        }
        return names;
    }

    private static boolean isGeneratedSchema(Schema schema) {
        return schema.getProperties() != null
                || (schema.getEnum() != null && !schema.getEnum().isEmpty())
                || schema.getOneOf() != null
                || schema.getAllOf() != null
                || schema.getAnyOf() != null
                || "object".equals(schema.getType());
    }

    private static OpenAPI openAPIWithRequestBody(ObjectSchema payload) {
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
                                new MediaType().schema(payload))))
                .responses(new ApiResponses().addApiResponse(
                        "200",
                        new ApiResponse().description("sent").content(
                                new Content().addMediaType(
                                        "application/json",
                                        new MediaType().schema(new ObjectSchema()
                                                .$ref("#/components/schemas/SendResponse"))))));
        ObjectSchema resourceNotFound = exception("RESOURCE_NOT_FOUND");
        return new OpenAPI()
                .servers(List.of(new Server().url("https://api.example.test/")))
                .components(new Components()
                        .addSchemas("SendResponse", new ObjectSchema()
                                .addProperty("accepted", new StringSchema()))
                        .addSchemas("ResourceNotFoundException", resourceNotFound))
                .paths(new Paths().addPathItem(
                        "/items/{id}",
                        new PathItem().post(operation)));
    }

    private static void generate(OpenAPI openAPI, Path output) {
        GlobalSettings.setProperty("apiTests", "false");
        GlobalSettings.setProperty("apiDocs", "false");
        GlobalSettings.setProperty("modelTests", "false");
        GlobalSettings.setProperty("modelDocs", "false");
        GlobalSettings.setProperty("supportingFiles", "");
        GlobalSettings.setProperty("models", "");
        GlobalSettings.setProperty("apis", "false");
        OmasTypeScriptClientCodegen codegen = new OmasTypeScriptClientCodegen();
        codegen.setOutputDir(output.toString());
        codegen.setApiPackage("");
        codegen.setModelPackage("models");
        codegen.additionalProperties().put("serviceName", "metrics");
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

    private static Schema property(ObjectSchema schema, String name) {
        return (Schema) schema.getProperties().get(name);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
