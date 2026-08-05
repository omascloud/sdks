package cloud.omas.sdk.generator.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class ApiErrorCatalog {

    private ApiErrorCatalog() {
    }

    public static List<ApiErrorDefinition> discover(OpenAPI openAPI) {
        List<ApiErrorDefinition> definitions = new ArrayList<>();
        for (Map.Entry<String, Schema> entry : OpenApiSchemas.schemas(openAPI).entrySet()) {
            if (!OpenApiSchemas.isApiException(entry.getKey(), entry.getValue())) {
                continue;
            }
            Schema<?> errorCode = OpenApiSchemas.property(entry.getValue(), "errorCode");
            Schema<?> extraData = OpenApiSchemas.property(entry.getValue(), "extraData");
            definitions.add(new ApiErrorDefinition(
                    entry.getKey(),
                    errorCode.getEnum().get(0).toString(),
                    OpenApiSchemas.referencedSchemaName(extraData)));
        }
        definitions.sort(Comparator.comparing(ApiErrorDefinition::errorCode)
                .thenComparing(ApiErrorDefinition::schemaName));
        return List.copyOf(definitions);
    }

    public static List<String> errorResponseSchemaNames(OpenAPI openAPI) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Schema> entry : OpenApiSchemas.schemas(openAPI).entrySet()) {
            if (OpenApiSchemas.isErrorResponse(entry.getValue())) {
                names.add(entry.getKey());
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }
}
