package cloud.omas.sdk.generator.typescript;

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
import org.openapitools.codegen.languages.TypeScriptFetchClientCodegen;
import org.openapitools.codegen.languages.TypeScriptFetchClientCodegen.ExtendedCodegenModel;
import org.openapitools.codegen.utils.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class OmasTypeScriptClientCodegen extends TypeScriptFetchClientCodegen {
    private static final String LOCATION = "x-sdk-location";
    private static final String REQUEST_CLASS = "x-sdk-request-class";

    private String serviceName = "service";
    private final Map<String, String> requestOperationIds = new LinkedHashMap<>();
    private final List<Map<String, Object>> operationRequests = new ArrayList<>();
    private final List<Map<String, Object>> clientOperations = new ArrayList<>();
    private final Set<String> clientModelImports = new TreeSet<>();
    private final Set<String> modelExports = new TreeSet<>();
    private final Set<String> generatedSchemaNames = new HashSet<>();
    private final Set<String> processedOperationRequests = new HashSet<>();

    public OmasTypeScriptClientCodegen() {
        super();
        templateDir = "omas-typescript";
        embeddedTemplateDir = "typescript-fetch";
        modelTemplateFiles.clear();
        apiTemplateFiles.clear();
        supportingFiles.clear();
        cliOptions.add(CliOption.newString(
                "serviceName",
                "Product service name used for the generated client."));
    }

    @Override
    public String getName() {
        return "omas-typescript";
    }

    @Override
    public String getHelp() {
        return "Generates Omas TypeScript service clients and structural models.";
    }

    @Override
    public void processOpts() {
        super.processOpts();
        serviceName = String.valueOf(additionalProperties.getOrDefault("serviceName", "service"));
        additionalProperties.put("serviceName", serviceName);
        modelTemplateFiles.clear();
        modelTemplateFiles.put("model.mustache", ".ts");
        apiTemplateFiles.clear();
        supportingFiles.clear();
        supportingFiles.add(new SupportingFile("validation.mustache", "", "validation.ts"));
        supportingFiles.add(new SupportingFile("api.mustache", "", serviceName + "-client.ts"));
        supportingFiles.add(new SupportingFile("api_errors.mustache", "", "api-errors.ts"));
        supportingFiles.add(new SupportingFile("metadata.mustache", "", "metadata.ts"));
        supportingFiles.add(new SupportingFile("index.mustache", "", "index.ts"));
        additionalProperties.put("operationRequests", operationRequests);
        additionalProperties.put("clientOperations", clientOperations);
        additionalProperties.put("clientModelImports", clientModelImports);
        additionalProperties.put("modelExports", modelExports);
        additionalProperties.put("serviceClass", StringUtils.camelize(serviceName));
        additionalProperties.put("clientClass", StringUtils.camelize(serviceName) + "Client");
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
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

    @Override
    public ExtendedCodegenModel fromModel(String name, Schema schema) {
        ExtendedCodegenModel model = super.fromModel(name, schema);
        model.imports.clear();
        if (!model.isAlias) {
            modelExports.add(model.classname);
        }
        addReferencedImports(model, schema);
        if (name.endsWith("OperationRequest")) {
            model.vars.removeIf(property -> OperationRequestFlattener.EMPTY_REQUEST_PLACEHOLDER
                    .equals(property.baseName));
            model.isAlias = false;
            model.isFreeFormObject = false;
        }
        if (requestOperationIds.containsKey(name) && processedOperationRequests.add(name)) {
            operationRequests.add(operationRequest(model, requestOperationIds.get(name)));
        }
        for (CodegenProperty property : model.vars) {
            property.dataType = readonlyType(property.dataType);
            property.datatypeWithEnum = readonlyType(property.datatypeWithEnum);
            if (property.enumName != null && property.enumName.endsWith("Enum")) {
                property.enumName = property.enumName.substring(
                        0,
                        property.enumName.length() - "Enum".length());
                property.datatypeWithEnum = property.datatypeWithEnum.replaceFirst("Enum$", "");
            }
            if (property.complexType != null && !property.complexType.equals(model.classname)) {
                model.imports.add(property.complexType);
            }
            if (property.items != null
                    && property.items.complexType != null
                    && !property.items.complexType.equals(model.classname)) {
                model.imports.add(property.items.complexType);
            }
        }
        model.vendorExtensions.put("x-sdk-imports", new TreeSet<>(model.imports));
        model.dataType = readonlyType(model.dataType);
        return model;
    }

    @Override
    public String toModelFilename(String name) {
        return StringUtils.camelize(name);
    }

    private Map<String, Object> operationRequest(CodegenModel model, String operationId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("className", model.classname);
        request.put("operationId", operationId);
        request.put("operationName", model.classname.substring(
                0,
                model.classname.length() - "OperationRequest".length()));
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CodegenProperty property : model.vars) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", property.name);
            field.put("baseName", property.baseName);
            field.put("required", property.required);
            field.put("requiredString", property.required && property.isString);
            field.put("minLength", property.minLength);
            field.put("maxLength", property.maxLength);
            field.put("pattern", escapedPattern(property.pattern));
            field.put("minimum", property.minimum);
            field.put("maximum", property.maximum);
            field.put("minItems", property.minItems);
            field.put("maxItems", property.maxItems);
            field.put("uniqueItems", property.getUniqueItems());
            field.put("guard", guard(property));
            fields.add(field);
        }
        request.put("fields", fields);
        return request;
    }

    private String guard(CodegenProperty property) {
        if (property.required && !property.isNullable) {
            return "";
        }
        return "request." + property.name + " !== undefined && request."
                + property.name + " !== null && ";
    }

    private String escapedPattern(String pattern) {
        if (pattern == null) {
            return null;
        }
        String normalized = pattern;
        if (normalized.startsWith("/") && normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.replace("/", "\\/");
    }

    private String readonlyType(String dataType) {
        if (dataType == null) {
            return null;
        }
        return dataType
                .replace("Array<", "ReadonlyArray<")
                .replace("Set<", "ReadonlyArray<")
                .replace("{ [key: string]: ", "Readonly<Record<string, ")
                .replace("; }", ">>");
    }

    private void addWireLocations(Operation operation, OperationRequestDefinition request) {
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                Schema<?> property = (Schema<?>) request.schema()
                        .getProperties()
                        .get(parameter.getName());
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
        String operationName = StringUtils.camelize(operation.getOperationId());
        generated.put("operationId", operation.getOperationId());
        generated.put("operationName", operationName);
        generated.put("requestType", request.schemaName());
        clientModelImports.add(request.schemaName());
        generated.put("httpMethod", method.name());
        generated.put("pathExpression", pathExpression(path, operation));
        generated.put("queryFields", parameterFields(operation, "query"));
        generated.put("headerFields", parameterFields(operation, "header"));
        List<Map<String, Object>> bodyFields = new ArrayList<>();
        for (String propertyName : request.schema().getProperties().keySet()) {
            if (request.bodyFields().contains(propertyName)) {
                bodyFields.add(Map.of("name", toVarName(propertyName), "baseName", propertyName));
            }
        }
        generated.put("bodyFields", bodyFields);
        generated.put("hasBody", !bodyFields.isEmpty());
        String returnType = returnType(operation);
        generated.put("returnType", returnType == null ? "void" : returnType);
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
                            "${encodeURIComponent(request." + toVarName(parameter.getName()) + ")}");
                }
            }
        }
        return "`" + expression + "`";
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
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", toVarName(parameter.getName()));
            field.put("baseName", parameter.getName());
            field.put("required", Boolean.TRUE.equals(parameter.getRequired()));
            field.put("array", parameter.getSchema() != null
                    && "array".equals(parameter.getSchema().getType()));
            fields.add(field);
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
            MediaType mediaType = entry.getValue().getContent().get("application/json");
            if (mediaType == null || mediaType.getSchema() == null) {
                return null;
            }
            Schema<?> schema = mediaType.getSchema();
            if (schema.get$ref() != null) {
                return toModelName(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
            }
            return readonlyType(getTypeDeclaration(schema));
        }
        return null;
    }

    private boolean isGeneratedSchema(Schema<?> schema) {
        return schema.getProperties() != null
                || (schema.getEnum() != null && !schema.getEnum().isEmpty())
                || schema.getOneOf() != null
                || schema.getAllOf() != null
                || schema.getAnyOf() != null
                || "object".equals(schema.getType());
    }

    private void collectGeneratedSchemaNames(OpenAPI openAPI) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return;
        }
        openAPI.getComponents().getSchemas().forEach((name, schema) -> {
            if (isGeneratedSchema(schema)) {
                generatedSchemaNames.add(name);
            }
        });
    }

    private void addReferencedImports(CodegenModel model, Schema<?> schema) {
        if (schema.getProperties() == null) {
            return;
        }
        for (Object value : schema.getProperties().values()) {
            Schema<?> property = (Schema<?>) value;
            addReferencedImport(model, property);
            if (property.getItems() != null) {
                addReferencedImport(model, property.getItems());
            }
        }
    }

    private void addReferencedImport(CodegenModel model, Schema<?> schema) {
        if (schema.get$ref() == null) {
            return;
        }
        String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        if (generatedSchemaNames.contains(name) && !name.equals(model.classname)) {
            model.imports.add(toModelName(name));
        }
    }

    private void configureServiceErrors(OpenAPI openAPI) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ApiErrorDefinition definition : ApiErrorCatalog.discover(openAPI)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("schemaName", definition.schemaName());
            error.put("type", definition.schemaName().substring(
                    0,
                    definition.schemaName().length() - "Exception".length()) + "Error");
            error.put("errorCode", definition.errorCode());
            error.put("hasDetails", definition.detailsSchemaName() != null);
            error.put("detailsType", definition.detailsSchemaName());
            errors.add(error);
        }
        additionalProperties.put("serviceErrors", errors);
        additionalProperties.put("hasServiceErrors", !errors.isEmpty());
    }
}
