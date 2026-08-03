package cloud.omas.sdk.generator.common;

public record ApiErrorDefinition(
        String schemaName,
        String errorCode,
        String detailsSchemaName) {
}
