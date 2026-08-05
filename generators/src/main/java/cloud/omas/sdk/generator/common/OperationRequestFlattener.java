package cloud.omas.sdk.generator.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.openapitools.codegen.utils.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OperationRequestFlattener {
    public static final String EMPTY_REQUEST_PLACEHOLDER = "_sdkPlaceholder";

    private OperationRequestFlattener() {
    }

    public static OperationRequestDefinition flatten(OpenAPI openAPI, Operation operation) {
        String operationId = operation.getOperationId();
        String schemaName = StringUtils.camelize(operationId) + "OperationRequest";
        ObjectSchema request = new ObjectSchema();
        request.setAdditionalProperties(false);
        request.setDescription(operation.getSummary() == null
                ? "Parameters for the " + operationId + " operation."
                : "Parameters for " + operation.getSummary() + ".");
        List<String> required = new ArrayList<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        Set<String> parameterNames = new LinkedHashSet<>();
        Set<String> bodyFields = new LinkedHashSet<>();

        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if ("cookie".equals(parameter.getIn())) {
                    throw new IllegalArgumentException("Cookie parameters are not supported: " + operationId);
                }
                parameterNames.add(parameter.getName());
                request.addProperty(parameter.getName(), parameter.getSchema());
                if (parameter.getDescription() != null) {
                    descriptions.put(parameter.getName(), parameter.getDescription());
                }
                if (Boolean.TRUE.equals(parameter.getRequired())) {
                    required.add(parameter.getName());
                }
            }
        }

        RequestBody body = operation.getRequestBody();
        if (body != null) {
            if (body.getContent() == null || body.getContent().get("application/json") == null) {
                throw new IllegalArgumentException(
                        "Only application/json request bodies are supported: " + operationId);
            }
            Schema<?> bodySchema = OpenApiSchemas.resolve(
                    openAPI,
                    body.getContent().get("application/json").getSchema(),
                    operationId);
            if (!(bodySchema instanceof ObjectSchema) && !"object".equals(bodySchema.getType())) {
                throw new IllegalArgumentException(
                        "Only JSON object request bodies can be flattened: " + operationId);
            }
            if (bodySchema.getProperties() != null) {
                bodySchema.getProperties().forEach((name, property) -> {
                    if (parameterNames.contains(name)) {
                        throw new IllegalArgumentException(
                                "Request property '" + name
                                        + "' is defined as both a parameter and a JSON body property: "
                                        + operationId);
                    }
                    request.addProperty(name, (Schema<?>) property);
                    bodyFields.add(name);
                });
            }
            if (bodySchema.getRequired() != null) {
                required.addAll(bodySchema.getRequired());
            }
        }

        if (!required.isEmpty()) {
            request.setRequired(required);
        }
        if (request.getProperties() == null || request.getProperties().isEmpty()) {
            request.addProperty(EMPTY_REQUEST_PLACEHOLDER, new BooleanSchema());
        }
        return new OperationRequestDefinition(schemaName, request, descriptions, bodyFields);
    }
}
