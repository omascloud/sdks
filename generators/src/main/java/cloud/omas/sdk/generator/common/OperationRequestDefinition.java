package cloud.omas.sdk.generator.common;

import io.swagger.v3.oas.models.media.Schema;

import java.util.Map;
import java.util.Set;

public record OperationRequestDefinition(
        String schemaName,
        Schema<?> schema,
        Map<String, String> parameterDescriptions,
        Set<String> bodyFields) {

    public OperationRequestDefinition {
        parameterDescriptions = Map.copyOf(parameterDescriptions);
        bodyFields = Set.copyOf(bodyFields);
    }
}
