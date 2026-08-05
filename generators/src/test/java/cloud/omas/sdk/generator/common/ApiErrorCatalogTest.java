package cloud.omas.sdk.generator.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

public class ApiErrorCatalogTest {

    @Test
    public void testDiscoversErrorsInDeterministicErrorCodeOrder() {
        Components components = new Components()
                .addSchemas("ResourceNotFoundExceptionDetails", new ObjectSchema())
                .addSchemas("ResourceNotFoundException", exception(
                        "RESOURCE_NOT_FOUND",
                        nullableReference("#/components/schemas/ResourceNotFoundExceptionDetails")))
                .addSchemas("AccessDeniedException", exception("ACCESS_DENIED", null))
                .addSchemas("ResourceLimitExceededExceptionDetails", new ObjectSchema())
                .addSchemas("ResourceLimitExceededException", exception(
                        "RESOURCE_LIMIT_EXCEEDED",
                        new Schema<>().$ref("#/components/schemas/ResourceLimitExceededExceptionDetails")))
                .addSchemas("ListMetricsErrorResponse", errorResponse(
                        "ResourceNotFoundException",
                        "AccessDeniedException"));
        OpenAPI openAPI = new OpenAPI().components(components);

        List<ApiErrorDefinition> definitions = ApiErrorCatalog.discover(openAPI);

        assertEquals(definitions.stream().map(ApiErrorDefinition::errorCode).toList(),
                List.of("ACCESS_DENIED", "RESOURCE_LIMIT_EXCEEDED", "RESOURCE_NOT_FOUND"));
        assertNull(definitions.get(0).detailsSchemaName());
        assertEquals(definitions.get(1).detailsSchemaName(), "ResourceLimitExceededExceptionDetails");
        assertEquals(definitions.get(2).detailsSchemaName(), "ResourceNotFoundExceptionDetails");
        assertEquals(ApiErrorCatalog.errorResponseSchemaNames(openAPI), List.of("ListMetricsErrorResponse"));
        assertThrows(UnsupportedOperationException.class,
                () -> definitions.add(new ApiErrorDefinition("FutureException", "FUTURE", null)));
    }

    @Test
    public void testIgnoresMalformedAndNonExceptionSchemas() {
        ObjectSchema missingError = new ObjectSchema();
        missingError.addProperty("errorCode", new StringSchema()._enum(List.of("MISSING_ERROR")));
        ObjectSchema multipleCodes = exception("FIRST", null);
        multipleCodes.setProperties(exception("FIRST", null).getProperties());
        StringSchema errorCodes = (StringSchema) multipleCodes.getProperties().get("errorCode");
        errorCodes.setEnum(List.of("FIRST", "SECOND"));
        ObjectSchema wrongSuffix = exception("WRONG_SUFFIX", null);
        OpenAPI openAPI = new OpenAPI().components(new Components()
                .addSchemas("MissingErrorException", missingError)
                .addSchemas("MultipleCodesException", multipleCodes)
                .addSchemas("WrongSuffix", wrongSuffix));

        assertEquals(ApiErrorCatalog.discover(openAPI), List.of());
        assertEquals(ApiErrorCatalog.errorResponseSchemaNames(openAPI), List.of());
    }

    private static ObjectSchema exception(String errorCode, Schema<?> details) {
        ObjectSchema exception = new ObjectSchema();
        exception.addProperty("error", new StringSchema());
        exception.addProperty("errorCode", new StringSchema()._enum(List.of(errorCode)));
        if (details != null) {
            exception.addProperty("extraData", details);
        }
        return exception;
    }

    private static Schema<Object> nullableReference(String reference) {
        Schema<Object> schema = new Schema<>();
        schema.setNullable(true);
        schema.addAllOfItem(new Schema<>().$ref(reference));
        return schema;
    }

    private static Schema<Object> errorResponse(String... exceptionNames) {
        Schema<Object> response = new Schema<>();
        response.setOneOf(java.util.Arrays.stream(exceptionNames)
                .map(name -> new Schema<>().$ref("#/components/schemas/" + name))
                .toList());
        return response;
    }
}
