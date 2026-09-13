package cloud.omas.sdk.generator.python;

import cloud.omas.sdk.generator.common.ApiErrorCatalog;
import cloud.omas.sdk.generator.common.ApiErrorDefinition;
import cloud.omas.sdk.generator.common.OperationRequestDefinition;
import cloud.omas.sdk.generator.common.OperationRequestFlattener;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.PythonClientCodegen;
import org.openapitools.codegen.utils.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class OmasPythonClientCodegen extends PythonClientCodegen {
    private static final String LOCATION = "x-sdk-location";
    private static final String REQUEST_CLASS = "x-sdk-request-class";

    private String serviceName = "service";
    private final Map<String, String> requestOperationIds = new LinkedHashMap<>();
    private final List<Map<String, Object>> pythonModels = new ArrayList<>();
    private final List<Map<String, Object>> pythonAliases = new ArrayList<>();
    private final List<Map<String, Object>> clientOperations = new ArrayList<>();
    private final Set<String> clientModelImports = new TreeSet<>();
    private final Set<String> generatedSchemaNames = new HashSet<>();
    private final Set<String> processedModels = new HashSet<>();
    private final Map<String, String> compositionConditions = new LinkedHashMap<>();

    public OmasPythonClientCodegen() {
        super();
        templateDir = "omas-python";
        embeddedTemplateDir = "python";
        modelTemplateFiles.clear();
        apiTemplateFiles.clear();
        supportingFiles.clear();
        cliOptions.add(CliOption.newString(
                "serviceName",
                "Product service name used for the generated client."));
    }

    @Override
    public String getName() {
        return "omas-python";
    }

    @Override
    public String getHelp() {
        return "Generates Omas Python service clients and Pydantic models.";
    }

    @Override
    public void processOpts() {
        super.processOpts();
        serviceName = String.valueOf(additionalProperties.getOrDefault("serviceName", "service"));
        additionalProperties.put("serviceName", serviceName);
        additionalProperties.put("serviceClass", StringUtils.camelize(serviceName));
        additionalProperties.put("pythonModels", pythonModels);
        additionalProperties.put("pythonAliases", pythonAliases);
        additionalProperties.put("clientOperations", clientOperations);
        additionalProperties.put("clientModelImports", clientModelImports);
        modelTemplateFiles.clear();
        apiTemplateFiles.clear();
        supportingFiles.clear();
        supportingFiles.add(new SupportingFile("models.mustache", "", "models.py"));
        supportingFiles.add(new SupportingFile("client.mustache", "", "client.py"));
        supportingFiles.add(new SupportingFile("errors.mustache", "", "errors.py"));
        supportingFiles.add(new SupportingFile("metadata.mustache", "", "metadata.py"));
        supportingFiles.add(new SupportingFile("init.mustache", "", "__init__.py"));
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        expandInlineCompositions(openAPI);
        collectGeneratedSchemaNames(openAPI);
        configureServiceErrors(openAPI);
        super.preprocessOpenAPI(openAPI);
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            additionalProperties.put("serverUrl", openAPI.getServers().get(0).getUrl());
        }
        if (openAPI.getPaths() == null) {
            return;
        }
        openAPI.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap()
                .forEach((method, operation) -> configureOperation(openAPI, path, method, operation)));
        collectGeneratedSchemaNames(openAPI);
    }

    private void expandInlineCompositions(OpenAPI openAPI) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return;
        }
        for (Map.Entry<String, Schema> schemaEntry :
                ((Map<String, Schema>) openAPI.getComponents().getSchemas()).entrySet()) {
            Schema<?> schema = schemaEntry.getValue();
            if (schema.getProperties() == null || schema.getOneOf() == null) {
                continue;
            }
            List<Schema<?>> variants = new ArrayList<>();
            for (Schema<?> reference : schema.getOneOf()) {
                Schema<?> variant = reference;
                if (reference.get$ref() != null) {
                    String referencedName = reference.get$ref()
                            .substring(reference.get$ref().lastIndexOf('/') + 1);
                    variant = openAPI.getComponents().getSchemas().get(referencedName);
                    if (variant == null) {
                        continue;
                    }
                }
                variants.add(variant);
                Map<String, Schema> properties = new LinkedHashMap<>();
                properties.putAll(schema.getProperties());
                if (variant.getProperties() != null) {
                    properties.putAll(variant.getProperties());
                }
                variant.setProperties(properties);
                Set<String> required = new LinkedHashSet<>();
                if (schema.getRequired() != null) {
                    required.addAll(schema.getRequired());
                }
                if (variant.getRequired() != null) {
                    required.addAll(variant.getRequired());
                }
                variant.setRequired(new ArrayList<>(required));
            }
            compositionConditions.put(schemaEntry.getKey(), oneOfCondition(variants));
        }
    }

    @Override
    public CodegenModel fromModel(String name, Schema schema) {
        CodegenModel model = super.fromModel(name, schema);
        if (!generatedSchemaNames.contains(name) || !processedModels.add(name)) {
            return model;
        }
        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("className", model.classname);
        generated.put("description", pythonDoc(model.description));
        generated.put("enum", model.isEnum);
        boolean composedObject = !model.oneOf.isEmpty() && model.vars != null && !model.vars.isEmpty();
        generated.put("composedObject", composedObject);
        if (composedObject) {
            generated.put("oneOfCondition", compositionConditions.getOrDefault(name, "True"));
        }
        boolean alias = (model.isAlias && !composedObject)
                || (!model.oneOf.isEmpty() && !composedObject)
                || model.getIsAnyType();
        generated.put("alias", alias);
        if (model.isEnum) {
            List<Map<String, Object>> values = new ArrayList<>();
            if (schema.getEnum() != null) {
                for (Object value : schema.getEnum()) {
                    values.add(Map.of(
                            "name", pythonEnumName(value),
                            "value", pythonLiteral(value)));
                }
            }
            generated.put("values", values);
        } else if (alias) {
            generated.put("rootType", constrainedRootType(model, schema));
        } else {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (CodegenProperty property : model.vars) {
                if (OperationRequestFlattener.EMPTY_REQUEST_PLACEHOLDER.equals(property.baseName)) {
                    continue;
                }
                fields.add(field(property));
            }
            generated.put("fields", fields);
        }
        if (Boolean.TRUE.equals(generated.get("alias"))) {
            pythonAliases.add(generated);
        } else {
            pythonModels.add(generated);
        }
        return model;
    }

    private Map<String, Object> field(CodegenProperty property) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", "field".equals(property.baseName) ? "field" : property.name);
        field.put("baseName", property.baseName);
        String type = pythonType(property.dataType);
        if (Boolean.TRUE.equals(property.getUniqueItems())) {
            type = "Annotated[" + type + ", AfterValidator(_unique_items)]";
        }
        field.put("type", nullableType(type, !property.required || property.isNullable));
        field.put("required", property.required);
        field.put("optional", !property.required);
        field.put("description", pythonDoc(property.description));
        List<String> options = new ArrayList<>();
        if (!property.name.equals(property.baseName)) {
            options.add("alias=\"" + property.baseName + "\"");
        }
        if (property.minLength != null) {
            options.add("min_length=" + property.minLength);
        }
        if (property.maxLength != null) {
            options.add("max_length=" + property.maxLength);
        }
        if (property.pattern != null) {
            String pattern = property.pattern;
            if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length() > 1) {
                pattern = pattern.substring(1, pattern.length() - 1);
            }
            options.add("pattern=r\"" + pattern.replace("\"", "\\\"") + "\"");
        }
        if (property.minimum != null) {
            options.add("ge=" + property.minimum);
        }
        if (property.maximum != null) {
            options.add("le=" + property.maximum);
        }
        if (property.minItems != null) {
            options.add("min_length=" + property.minItems);
        }
        if (property.maxItems != null) {
            options.add("max_length=" + property.maxItems);
        }
        field.put("fieldOptions", String.join(", ", options));
        field.put("hasOptions", !options.isEmpty());
        return field;
    }

    private String pythonLiteral(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue ? "True" : "False";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return "\"" + value.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private String oneOfCondition(List<Schema<?>> schemaVariants) {
        List<String> variants = new ArrayList<>();
        for (Schema<?> variant : schemaVariants) {
            List<String> conditions = new ArrayList<>();
            if (variant.getProperties() != null) {
                for (Map.Entry<String, Schema> property :
                        ((Map<String, Schema>) variant.getProperties()).entrySet()) {
                    if (property.getValue().getEnum() != null
                            && property.getValue().getEnum().size() == 1) {
                        conditions.add("self." + toVarName(property.getKey()) + " == "
                                + pythonLiteral(property.getValue().getEnum().get(0)));
                    }
                }
            }
            if (variant.getRequired() != null) {
                for (String required : variant.getRequired()) {
                    conditions.add("self." + toVarName(required) + " is not None");
                }
            }
            variants.add("(" + String.join(" and ", conditions) + ")");
        }
        return "sum((" + String.join(", ", variants) + ")) != 1";
    }

    private String pythonEnumName(Object value) {
        String name = String.valueOf(value)
                .replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_")
                .toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return "EMPTY";
        }
        if (Character.isDigit(name.charAt(0))) {
            return "VALUE_" + name;
        }
        return name;
    }

    private String rootType(CodegenModel model) {
        if (model.oneOf != null && !model.oneOf.isEmpty()) {
            return model.oneOf.stream().map(this::pythonType).reduce((a, b) -> a + " | " + b).orElse("Any");
        }
        if (model.anyOf != null && !model.anyOf.isEmpty()) {
            return model.anyOf.stream().map(this::pythonType).reduce((a, b) -> a + " | " + b).orElse("Any");
        }
        return pythonType(model.dataType == null ? "Any" : model.dataType);
    }

    private String constrainedRootType(CodegenModel model, Schema schema) {
        String type = rootType(model);
        List<String> options = new ArrayList<>();
        if (schema.getMinLength() != null) {
            options.add("min_length=" + schema.getMinLength());
        }
        if (schema.getMaxLength() != null) {
            options.add("max_length=" + schema.getMaxLength());
        }
        if (schema.getPattern() != null) {
            String pattern = schema.getPattern();
            if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length() > 1) {
                pattern = pattern.substring(1, pattern.length() - 1);
            }
            options.add("pattern=r\"" + pattern.replace("\"", "\\\"") + "\"");
        }
        if (schema.getMinimum() != null) {
            options.add("ge=" + schema.getMinimum());
        }
        if (schema.getMaximum() != null) {
            options.add("le=" + schema.getMaximum());
        }
        if (schema.getMinItems() != null) {
            options.add("min_length=" + schema.getMinItems());
        }
        if (schema.getMaxItems() != null) {
            options.add("max_length=" + schema.getMaxItems());
        }
        return options.isEmpty()
                ? type
                : "Annotated[" + type + ", Field(" + String.join(", ", options) + ")]";
    }

    private String pythonType(String value) {
        if (value == null || value.isBlank()) {
            return "Any";
        }
        String normalized = value
                .replace("StrictStr", "str")
                .replace("StrictInt", "int")
                .replace("StrictFloat", "float")
                .replace("StrictBool", "bool");
        if (normalized.startsWith("List[") && normalized.endsWith("]")) {
            return "tuple[" + pythonType(normalized.substring(5, normalized.length() - 1)) + ", ...]";
        }
        if (normalized.startsWith("Dict[") && normalized.endsWith("]")) {
            String entries = normalized.substring(5, normalized.length() - 1);
            int comma = entries.indexOf(',');
            if (comma > 0) {
                String key = pythonType(entries.substring(0, comma).trim());
                String item = pythonType(entries.substring(comma + 1).trim());
                return "Annotated[Mapping[" + key + ", " + item
                        + "], AfterValidator(_freeze_mapping), PlainSerializer(_serialize_mapping)]";
            }
        }
        if (normalized.startsWith("Set[") && normalized.endsWith("]")) {
            return "frozenset[" + pythonType(normalized.substring(4, normalized.length() - 1)) + "]";
        }
        return normalized.replace("Union[", "Union[");
    }

    private String nullableType(String value, boolean nullable) {
        if (!nullable || value.contains("None") || value.endsWith(" | None")) {
            return value;
        }
        return value + " | None";
    }

    private String pythonDoc(String value) {
        return value == null ? "" : value.replace("\"\"\"", "\\\"\\\"\\\"");
    }

    private void addWireLocations(Operation operation, OperationRequestDefinition request) {
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                Schema<?> property = (Schema<?>) request.schema().getProperties().get(parameter.getName());
                property.addExtension(LOCATION, parameter.getIn());
            }
        }
        for (String bodyField : request.bodyFields()) {
            Schema<?> property = (Schema<?>) request.schema().getProperties().get(bodyField);
            property.addExtension(LOCATION, "body");
        }
    }

    private void configureOperation(
            OpenAPI openAPI,
            String path,
            HttpMethod method,
            Operation operation) {
        operation.setTags(List.of(serviceName));
        OperationRequestDefinition request = OperationRequestFlattener.flatten(openAPI, operation);
        addWireLocations(operation, request);
        request.schema().addExtension("x-sdk-operation-request", true);
        operation.addExtension(REQUEST_CLASS, request.schemaName());
        requestOperationIds.put(request.schemaName(), operation.getOperationId());
        openAPI.getComponents().addSchemas(request.schemaName(), request.schema());

        Map<String, Object> generated = new LinkedHashMap<>();
        generated.put("operationId", operation.getOperationId());
        generated.put("methodName", toOperationId(operation.getOperationId()));
        generated.put("requestType", request.schemaName());
        clientModelImports.add(request.schemaName());
        generated.put("httpMethod", method.name());
        generated.put("pathExpression", pathExpression(path, operation));
        generated.put("queryFields", parameterFields(operation, "query"));
        generated.put("headerFields", parameterFields(operation, "header"));
        List<Map<String, Object>> bodyFields = new ArrayList<>();
        for (String propertyName : request.schema().getProperties().keySet()) {
            if (request.bodyFields().contains(propertyName)) {
                bodyFields.add(Map.of(
                        "name", toVarName(propertyName),
                        "baseName", propertyName,
                        "required", request.schema().getRequired() != null
                                && request.schema().getRequired().contains(propertyName)));
            }
        }
        generated.put("bodyFields", bodyFields);
        generated.put("hasBody", !bodyFields.isEmpty());
        generated.put("needsWire", !bodyFields.isEmpty()
                || (operation.getParameters() != null && operation.getParameters().stream()
                        .anyMatch(parameter -> "query".equals(parameter.getIn())
                                || "header".equals(parameter.getIn()))));
        String returnType = returnType(operation);
        generated.put("returnType", returnType == null ? "None" : returnType);
        generated.put("hasReturn", returnType != null);
        if (returnType != null) {
            clientModelImports.add(returnType);
        }
        clientOperations.add(generated);
    }

    private String pathExpression(String path, Operation operation) {
        String expression = path.replaceFirst("^/", "");
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if ("path".equals(parameter.getIn())) {
                    expression = expression.replace(
                            "{" + parameter.getName() + "}",
                            "{quote(str(request." + toVarName(parameter.getName()) + "), safe='')}");
                }
            }
        }
        return (expression.contains("{") ? "f\"" : "\"") + expression + "\"";
    }

    private List<Map<String, Object>> parameterFields(Operation operation, String location) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (operation.getParameters() == null) {
            return fields;
        }
        for (Parameter parameter : operation.getParameters()) {
            if (!location.equals(parameter.getIn())) {
                continue;
            }
            fields.add(Map.of(
                    "name", toVarName(parameter.getName()),
                    "baseName", parameter.getName(),
                    "required", Boolean.TRUE.equals(parameter.getRequired()),
                    "array", parameter.getSchema() != null
                            && "array".equals(parameter.getSchema().getType())));
        }
        return fields;
    }

    private String returnType(Operation operation) {
        if (operation.getResponses() == null) {
            return null;
        }
        for (Map.Entry<String, ApiResponse> entry : operation.getResponses().entrySet()) {
            if (!entry.getKey().startsWith("2") || entry.getValue().getContent() == null) {
                continue;
            }
            for (MediaType mediaType : entry.getValue().getContent().values()) {
                Schema<?> schema = mediaType.getSchema();
                if (schema != null) {
                    String referenced = referencedSchemaName(schema);
                    return referenced == null ? pythonType(getTypeDeclaration(schema)) : referenced;
                }
            }
        }
        return null;
    }

    private String referencedSchemaName(Schema<?> schema) {
        if (schema.get$ref() == null) {
            return null;
        }
        String ref = schema.get$ref();
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private void configureServiceErrors(OpenAPI openAPI) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ApiErrorDefinition definition : ApiErrorCatalog.discover(openAPI)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("errorCode", definition.errorCode());
            error.put("type", definition.schemaName().replaceFirst("Exception$", "Error"));
            error.put("hasDetails", definition.detailsSchemaName() != null);
            error.put("detailsType", definition.detailsSchemaName());
            errors.add(error);
        }
        additionalProperties.put("serviceErrors", errors);
    }

    private void collectGeneratedSchemaNames(OpenAPI openAPI) {
        if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            generatedSchemaNames.addAll(openAPI.getComponents().getSchemas().keySet());
        }
    }
}
