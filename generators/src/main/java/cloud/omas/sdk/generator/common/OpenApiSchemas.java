package cloud.omas.sdk.generator.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class OpenApiSchemas {

    private OpenApiSchemas() {
    }

    public static Map<String, Schema> schemas(OpenAPI openAPI) {
        if (openAPI == null || openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return Map.of();
        }
        return openAPI.getComponents().getSchemas();
    }

    public static Schema<?> property(Schema<?> schema, String name) {
        return schema == null || schema.getProperties() == null
                ? null
                : (Schema<?>) schema.getProperties().get(name);
    }

    public static Schema<?> resolve(OpenAPI openAPI, Schema<?> schema, String context) {
        Schema<?> resolved = schema;
        Set<String> references = new HashSet<>();
        while (resolved != null && resolved.get$ref() != null) {
            String reference = resolved.get$ref();
            if (!references.add(reference)) {
                throw new IllegalArgumentException("Circular schema reference: " + context);
            }
            String name = schemaName(reference);
            resolved = schemas(openAPI).get(name);
        }
        if (resolved == null) {
            throw new IllegalArgumentException("Cannot resolve schema reference: " + context);
        }
        return resolved;
    }

    public static String referencedSchemaName(Schema<?> schema) {
        if (schema == null) {
            return null;
        }
        if (schema.get$ref() != null) {
            return schemaName(schema.get$ref());
        }
        if (schema.getAllOf() != null) {
            for (Schema<?> member : schema.getAllOf()) {
                String reference = referencedSchemaName(member);
                if (reference != null) {
                    return reference;
                }
            }
        }
        return null;
    }

    public static boolean isErrorResponse(Schema<?> schema) {
        return schema != null
                && schema.getOneOf() != null
                && !schema.getOneOf().isEmpty()
                && schema.getOneOf().stream().allMatch(member -> {
                    String reference = member.get$ref();
                    return reference != null && reference.endsWith("Exception");
                });
    }

    public static boolean isApiException(String name, Schema<?> schema) {
        if (name == null || !name.endsWith("Exception") || schema == null || schema.getProperties() == null) {
            return false;
        }
        Schema<?> errorCode = property(schema, "errorCode");
        return property(schema, "error") != null
                && errorCode != null
                && errorCode.getEnum() != null
                && errorCode.getEnum().size() == 1;
    }

    private static String schemaName(String reference) {
        return reference.substring(reference.lastIndexOf('/') + 1);
    }
}
