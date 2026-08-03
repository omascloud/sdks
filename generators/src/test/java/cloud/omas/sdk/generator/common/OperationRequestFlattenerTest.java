package cloud.omas.sdk.generator.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.CookieParameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class OperationRequestFlattenerTest {

    @Test
    public void testFlattensParametersAndJsonBodyIntoOneRequestSchema() {
        StringSchema message = new StringSchema();
        message.setDescription("Message body field.");
        ObjectSchema payload = new ObjectSchema();
        payload.addProperty("message", message);
        payload.setRequired(List.of("message"));
        OpenAPI openAPI = openAPI(payload);
        Operation operation = operation(openAPI);
        operation.addParametersItem(new QueryParameter()
                .name("limit")
                .description("Maximum items.")
                .schema(new Schema<>().type("integer")));

        OperationRequestDefinition definition = OperationRequestFlattener.flatten(openAPI, operation);

        assertEquals(definition.schemaName(), "SendOperationRequest");
        assertEquals(definition.schema().getProperties().keySet(), Set.of("id", "limit", "message"));
        assertEquals(definition.schema().getRequired(), List.of("id", "message"));
        assertSame(definition.schema().getProperties().get("message"), message);
        assertEquals(definition.parameterDescriptions().get("limit"), "Maximum items.");
        assertEquals(definition.bodyFields(), Set.of("message"));
        assertFalse(definition.schema().getProperties().containsKey("body"));
    }

    @Test
    public void testAddsPlaceholderForAnEmptyOperation() {
        OpenAPI openAPI = new OpenAPI().components(new Components());
        Operation operation = new Operation().operationId("ping");

        OperationRequestDefinition definition = OperationRequestFlattener.flatten(openAPI, operation);

        assertEquals(definition.schema().getProperties().keySet(),
                Set.of(OperationRequestFlattener.EMPTY_REQUEST_PLACEHOLDER));
        assertTrue(definition.bodyFields().isEmpty());
        assertTrue(definition.parameterDescriptions().isEmpty());
    }

    @Test
    public void testRejectsCookieParametersAndPropertyCollisions() {
        ObjectSchema payload = new ObjectSchema();
        payload.addProperty("id", new StringSchema());
        OpenAPI openAPI = openAPI(payload);
        Operation operation = operation(openAPI);
        operation.addParametersItem(new CookieParameter().name("session").schema(new StringSchema()));

        IllegalArgumentException cookieException = expectThrows(
                IllegalArgumentException.class,
                () -> OperationRequestFlattener.flatten(openAPI, operation));
        assertEquals(cookieException.getMessage(), "Cookie parameters are not supported: send");

        operation.setParameters(operation.getParameters().stream()
                .filter(parameter -> !"cookie".equals(parameter.getIn()))
                .toList());
        IllegalArgumentException collisionException = expectThrows(
                IllegalArgumentException.class,
                () -> OperationRequestFlattener.flatten(openAPI, operation));
        assertEquals(collisionException.getMessage(),
                "Request property 'id' is defined as both a parameter and a JSON body property: send");
    }

    @Test
    public void testRejectsUnsupportedRequestBodies() {
        OpenAPI openAPI = new OpenAPI().components(new Components());
        Operation nonJson = new Operation()
                .operationId("upload")
                .requestBody(new RequestBody().content(new Content().addMediaType(
                        "application/xml",
                        new MediaType().schema(new ObjectSchema()))));
        IllegalArgumentException nonJsonException = expectThrows(
                IllegalArgumentException.class,
                () -> OperationRequestFlattener.flatten(openAPI, nonJson));
        assertEquals(nonJsonException.getMessage(),
                "Only application/json request bodies are supported: upload");

        Operation scalar = new Operation()
                .operationId("send")
                .requestBody(jsonBody(new StringSchema()));
        IllegalArgumentException scalarException = expectThrows(
                IllegalArgumentException.class,
                () -> OperationRequestFlattener.flatten(openAPI, scalar));
        assertEquals(scalarException.getMessage(),
                "Only JSON object request bodies can be flattened: send");
    }

    @Test
    public void testResolvesBodyReferencesAndReportsResolutionFailures() {
        ObjectSchema payload = new ObjectSchema();
        payload.addProperty("message", new StringSchema());
        OpenAPI openAPI = new OpenAPI().components(new Components().addSchemas("Payload", payload));
        Operation operation = new Operation()
                .operationId("send")
                .requestBody(jsonBody(new Schema<>().$ref("#/components/schemas/Payload")));

        OperationRequestDefinition definition = OperationRequestFlattener.flatten(openAPI, operation);
        assertEquals(definition.bodyFields(), Set.of("message"));

        operation.setRequestBody(jsonBody(new Schema<>().$ref("#/components/schemas/Missing")));
        IllegalArgumentException missingException = expectThrows(
                IllegalArgumentException.class,
                () -> OperationRequestFlattener.flatten(openAPI, operation));
        assertEquals(missingException.getMessage(), "Cannot resolve schema reference: send");

        openAPI.getComponents()
                .addSchemas("First", new Schema<>().$ref("#/components/schemas/Second"))
                .addSchemas("Second", new Schema<>().$ref("#/components/schemas/First"));
        operation.setRequestBody(jsonBody(new Schema<>().$ref("#/components/schemas/First")));
        IllegalArgumentException circularException = expectThrows(
                IllegalArgumentException.class,
                () -> OperationRequestFlattener.flatten(openAPI, operation));
        assertEquals(circularException.getMessage(), "Circular schema reference: send");
    }

    private static OpenAPI openAPI(Schema<?> bodySchema) {
        OpenAPI openAPI = new OpenAPI().components(new Components());
        Operation operation = new Operation()
                .operationId("send")
                .summary("Send a payload")
                .addParametersItem(new PathParameter()
                        .name("id")
                        .description("Item identifier.")
                        .required(true)
                        .schema(new StringSchema()))
                .requestBody(jsonBody(bodySchema));
        openAPI.addExtension("x-test-operation", operation);
        return openAPI;
    }

    private static Operation operation(OpenAPI openAPI) {
        return (Operation) openAPI.getExtensions().get("x-test-operation");
    }

    private static RequestBody jsonBody(Schema<?> schema) {
        return new RequestBody().content(new Content().addMediaType(
                "application/json",
                new MediaType().schema(schema)));
    }
}
