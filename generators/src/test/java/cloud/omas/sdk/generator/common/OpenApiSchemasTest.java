package cloud.omas.sdk.generator.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class OpenApiSchemasTest {

    @Test
    public void testReturnsEmptySchemasWhenComponentsAreMissing() {
        assertEquals(OpenApiSchemas.schemas(new OpenAPI()), Map.of());
    }

    @Test
    public void testResolvesComponentReferences() {
        ObjectSchema payload = new ObjectSchema();
        OpenAPI openAPI = new OpenAPI().components(new Components().addSchemas("Payload", payload));

        Schema<?> resolved = OpenApiSchemas.resolve(
                openAPI,
                new Schema<>().$ref("#/components/schemas/Payload"),
                "send");

        assertSame(resolved, payload);
    }

    @Test
    public void testRejectsCircularAndMissingReferences() {
        Schema<?> first = new Schema<>().$ref("#/components/schemas/Second");
        Schema<?> second = new Schema<>().$ref("#/components/schemas/First");
        OpenAPI circular = new OpenAPI().components(new Components()
                .addSchemas("First", first)
                .addSchemas("Second", second));

        IllegalArgumentException circularException = expectThrows(
                IllegalArgumentException.class,
                () -> OpenApiSchemas.resolve(circular, first, "send"));
        assertEquals(circularException.getMessage(), "Circular schema reference: send");

        IllegalArgumentException missingException = expectThrows(
                IllegalArgumentException.class,
                () -> OpenApiSchemas.resolve(
                        new OpenAPI(),
                        new Schema<>().$ref("#/components/schemas/Missing"),
                        "send"));
        assertEquals(missingException.getMessage(), "Cannot resolve schema reference: send");
    }

    @Test
    public void testFindsDirectAndNullableAllOfReferenceNames() {
        Schema<?> direct = new Schema<>().$ref("#/components/schemas/Details");
        Schema<Object> nullableAllOf = new Schema<>();
        nullableAllOf.setNullable(true);
        nullableAllOf.addAllOfItem(direct);

        assertEquals(OpenApiSchemas.referencedSchemaName(direct), "Details");
        assertEquals(OpenApiSchemas.referencedSchemaName(nullableAllOf), "Details");
        assertNull(OpenApiSchemas.referencedSchemaName(new StringSchema()));
    }

    @Test
    public void testClassifiesErrorResponsesAndConcreteExceptions() {
        ObjectSchema exception = new ObjectSchema();
        exception.addProperty("error", new StringSchema());
        exception.addProperty("errorCode", new StringSchema()._enum(List.of("RESOURCE_NOT_FOUND")));
        Schema<Object> response = new Schema<>();
        response.setOneOf(List.of(
                new Schema<>().$ref("#/components/schemas/ResourceNotFoundException"),
                new Schema<>().$ref("#/components/schemas/AccessDeniedException")));

        assertTrue(OpenApiSchemas.isErrorResponse(response));
        assertTrue(OpenApiSchemas.isApiException("ResourceNotFoundException", exception));
        assertEquals(OpenApiSchemas.property(exception, "errorCode").getEnum(),
                List.of("RESOURCE_NOT_FOUND"));
    }
}
