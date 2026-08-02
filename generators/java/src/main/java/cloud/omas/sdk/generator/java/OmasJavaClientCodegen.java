package cloud.omas.sdk.generator.java;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.languages.JavaClientCodegen;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.utils.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OmasJavaClientCodegen extends JavaClientCodegen {

    private static final String REQUEST_CLASS = "x-sdk-request-class";
    private static final String EMPTY_REQUEST_PLACEHOLDER = "_sdkPlaceholder";
    private String serviceName = "service";
    private String exceptionPackage;
    private final Map<String, Map<String, Object>> apiExceptionDefinitions = new HashMap<>();
    private final Set<String> apiExceptionNames = new HashSet<>();
    private final Map<String, Map<String, String>> requestFieldDescriptions = new HashMap<>();
    private final Map<String, Set<String>> requestBodyFields = new HashMap<>();

    public OmasJavaClientCodegen() {
        super();
        embeddedTemplateDir = "omas-java";
        templateDir = "omas-java";
        modelTemplateFiles.clear();
        modelTemplateFiles.put("model.mustache", ".java");
        apiTemplateFiles.clear();
        apiTemplateFiles.put("api.mustache", ".java");
        apiTemplateFiles.put("asyncApi.mustache", ".java");
        apiTemplateFiles.put("exceptions.mustache", ".java");
        supportingFiles.clear();
        cliOptions.add(CliOption.newString("serviceName", "Product service name used for the generated client."));
    }

    @Override
    public String getName() {
        return "omas-java";
    }

    @Override
    public String getHelp() {
        return "Generates asynchronous and synchronous Omas Java service clients and immutable models.";
    }

    @Override
    public void processOpts() {
        setLibrary(APACHE);
        forceSerializationLibrary(SERIALIZATION_LIBRARY_JACKSON);
        setUseJakartaEe(true);
        setOpenApiNullable(false);
        super.processOpts();
        serviceName = String.valueOf(additionalProperties.getOrDefault("serviceName", "service"));
        additionalProperties.put("serviceName", serviceName);
        additionalProperties.put("clientClass", StringUtils.camelize(serviceName) + "Client");
        additionalProperties.put("asyncClientClass", StringUtils.camelize(serviceName) + "AsyncClient");
        supportingFiles.clear();
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        configureApiExceptions(openAPI);
        super.preprocessOpenAPI(openAPI);
        if (openAPI.getInfo() != null) {
            additionalProperties.put("serviceTitle", openAPI.getInfo().getTitle());
            additionalProperties.put("serviceDescription", openAPI.getInfo().getDescription());
        }
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            additionalProperties.put("serverUrl", openAPI.getServers().get(0).getUrl());
        }
        openAPI.getPaths().forEach((path, pathItem) -> operations(pathItem).forEach(operation -> {
            operation.setTags(List.of(serviceName));
            String requestClass = StringUtils.camelize(operation.getOperationId()) + "OperationRequest";
            operation.addExtension(REQUEST_CLASS, requestClass);
            openAPI.getComponents().addSchemas(requestClass, requestSchema(openAPI, operation, requestClass));
        }));
    }

    private Schema<?> requestSchema(OpenAPI openAPI, Operation operation, String requestClass) {
        ObjectSchema request = new ObjectSchema();
        request.setAdditionalProperties(false);
        request.setDescription(operation.getSummary() == null
                ? "Parameters for the " + operation.getOperationId() + " operation."
                : "Parameters for " + operation.getSummary() + ".");
        List<String> required = new ArrayList<>();
        Map<String, String> descriptions = new LinkedHashMap<>();
        Set<String> parameterNames = new HashSet<>();
        Set<String> bodyFields = new HashSet<>();
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if ("cookie".equals(parameter.getIn())) {
                    throw new IllegalArgumentException(
                            "Cookie parameters are not supported: " + operation.getOperationId());
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
                        "Only application/json request bodies are supported: " + operation.getOperationId());
            }
            Schema<?> bodySchema = resolveSchema(
                    openAPI,
                    body.getContent().get("application/json").getSchema(),
                    operation.getOperationId());
            if (!(bodySchema instanceof ObjectSchema) && !"object".equals(bodySchema.getType())) {
                throw new IllegalArgumentException(
                        "Only JSON object request bodies can be flattened: " + operation.getOperationId());
            }
            if (bodySchema.getProperties() != null) {
                bodySchema.getProperties().forEach((name, property) -> {
                    if (parameterNames.contains(name)) {
                        throw new IllegalArgumentException(
                                "Request property '" + name
                                        + "' is defined as both a parameter and a JSON body property: "
                                        + operation.getOperationId());
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
        requestFieldDescriptions.put(requestClass, descriptions);
        requestBodyFields.put(requestClass, bodyFields);
        return request;
    }

    private Schema<?> resolveSchema(OpenAPI openAPI, Schema<?> schema, String operationId) {
        Schema<?> resolved = schema;
        Set<String> references = new HashSet<>();
        while (resolved != null && resolved.get$ref() != null) {
            String reference = resolved.get$ref();
            if (!references.add(reference)) {
                throw new IllegalArgumentException(
                        "Circular request body schema reference: " + operationId);
            }
            String name = reference.substring(reference.lastIndexOf('/') + 1);
            resolved = openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null
                    ? null
                    : openAPI.getComponents().getSchemas().get(name);
        }
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "Cannot resolve JSON request body schema: " + operationId);
        }
        return resolved;
    }

    private List<Operation> operations(PathItem pathItem) {
        return pathItem.readOperations();
    }

    @Override
    public void addOperationToGroup(
            String tag,
            String resourcePath,
            Operation operation,
            CodegenOperation codegenOperation,
            Map<String, List<CodegenOperation>> operations) {
        super.addOperationToGroup(serviceName, resourcePath, operation, codegenOperation, operations);
    }

    @Override
    public String toApiName(String name) {
        return StringUtils.camelize(serviceName) + "Client";
    }

    @Override
    public String apiFilename(String templateName, String tag) {
        if ("asyncApi.mustache".equals(templateName)) {
            return apiFileFolder() + File.separator + additionalProperties.get("asyncClientClass") + ".java";
        }
        if ("exceptions.mustache".equals(templateName)) {
            return apiFileFolder() + File.separator + "exception" + File.separator + "ApiExceptions.java";
        }
        return super.apiFilename(templateName, tag);
    }

    @Override
    public String apiFilename(String templateName, String tag, String outputDirectory) {
        if ("asyncApi.mustache".equals(templateName)) {
            return outputDirectory + File.separator + additionalProperties.get("asyncClientClass") + ".java";
        }
        if ("exceptions.mustache".equals(templateName)) {
            return outputDirectory + File.separator + "exception" + File.separator + "ApiExceptions.java";
        }
        return super.apiFilename(templateName, tag, outputDirectory);
    }

    @Override
    public String modelFilename(String templateName, String modelName) {
        String filename = super.modelFilename(templateName, modelName);
        return apiExceptionNames.contains(modelName) ? moveToExceptionPackage(filename) : filename;
    }

    @Override
    public String modelFilename(String templateName, String modelName, String outputDirectory) {
        String filename = super.modelFilename(templateName, modelName, outputDirectory);
        return apiExceptionNames.contains(modelName) ? moveToExceptionPackage(filename) : filename;
    }

    private void configureApiExceptions(OpenAPI openAPI) {
        exceptionPackage = apiPackage() + ".exception";
        additionalProperties.put("exceptionPackage", exceptionPackage);
        mapExceptionSchema("ApiException", "cloud.omas.sdk.core.exception.ApiException");
        List<Map<String, Object>> exceptions = new ArrayList<>();
        Map<String, Schema> schemas = openAPI.getComponents() == null
                || openAPI.getComponents().getSchemas() == null
                ? Map.of()
                : openAPI.getComponents().getSchemas();
        schemas.forEach((name, schema) -> {
            if (isErrorResponse(schema)) {
                mapExceptionSchema(name, "cloud.omas.sdk.core.exception.ApiException");
            }
        });
        schemas.entrySet().stream()
                .filter(entry -> isApiException(entry.getKey(), entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = entry.getKey();
                    Schema<?> errorCode = property(entry.getValue(), "errorCode");
                    Map<String, Object> exception = new LinkedHashMap<>();
                    exception.put("name", name);
                    String type = apiExceptionType(name);
                    exception.put("type", type);
                    exception.put("errorCode", errorCode.getEnum().get(0).toString());
                    List<Map<String, Object>> fields = exceptionFields(schemas.get(name + "Details"));
                    exception.put("hasFields", !fields.isEmpty());
                    exception.put("fields", fields);
                    exceptions.add(exception);
                    if (generateApiException(name)) {
                        apiExceptionNames.add(name);
                        apiExceptionDefinitions.put(name, exception);
                        importMapping.put(name, exceptionPackage + "." + name);
                    } else {
                        mapExceptionSchema(name, type);
                    }
                    if (!fields.isEmpty()) {
                        mapExceptionSchema(name + "Details", type + ".Details");
                    }
                });
        additionalProperties.put("apiExceptions", exceptions);
    }

    protected String apiExceptionType(String name) {
        return name;
    }

    protected boolean generateApiException(String name) {
        return true;
    }

    private boolean isErrorResponse(Schema<?> schema) {
        return schema != null
                && schema.getOneOf() != null
                && !schema.getOneOf().isEmpty()
                && schema.getOneOf().stream().allMatch(member -> {
                    String reference = member.get$ref();
                    return reference != null && reference.endsWith("Exception");
                });
    }

    private boolean isApiException(String name, Schema<?> schema) {
        if (!name.endsWith("Exception") || schema == null || schema.getProperties() == null) {
            return false;
        }
        Schema<?> errorCode = property(schema, "errorCode");
        return property(schema, "error") != null
                && errorCode != null
                && errorCode.getEnum() != null
                && errorCode.getEnum().size() == 1;
    }

    private List<Map<String, Object>> exceptionFields(Schema<?> details) {
        if (details == null || details.getProperties() == null) {
            return List.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        details.getProperties().forEach((name, value) -> {
            Schema<?> field = (Schema<?>) value;
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("name", name);
            definition.put("type", exceptionFieldType(field));
            definition.put("description", field.getDescription());
            fields.add(definition);
        });
        for (int index = 0; index < fields.size(); index++) {
            fields.get(index).put("last", index == fields.size() - 1);
        }
        return fields;
    }

    private String exceptionFieldType(Schema<?> field) {
        if ("string".equals(field.getType())) {
            return "String";
        }
        if ("integer".equals(field.getType()) && "int32".equals(field.getFormat())) {
            return "int";
        }
        if ("integer".equals(field.getType()) && "int64".equals(field.getFormat())) {
            return "long";
        }
        throw new IllegalArgumentException(
                "Unsupported API exception field type: " + field.getType() + "/" + field.getFormat());
    }

    private Schema<?> property(Schema<?> schema, String name) {
        return schema == null || schema.getProperties() == null
                ? null
                : (Schema<?>) schema.getProperties().get(name);
    }

    private void mapExceptionSchema(String schemaName, String className) {
        schemaMapping.put(schemaName, className);
        importMapping.put(schemaName, className);
    }

    @Override
    public CodegenModel fromModel(String name, Schema schema) {
        CodegenModel model = super.fromModel(name, schema);
        Map<String, Object> exception = apiExceptionDefinitions.get(name);
        if (exception != null) {
            model.vendorExtensions.put("x-sdk-api-exception", true);
            model.vendorExtensions.put("x-sdk-error-code", exception.get("errorCode"));
            model.vendorExtensions.put("x-sdk-has-fields", exception.get("hasFields"));
            model.vendorExtensions.put("x-sdk-fields", exception.get("fields"));
        }
        if (name.endsWith("OperationRequest")
                && schema.getProperties() != null
                && schema.getProperties().containsKey(EMPTY_REQUEST_PLACEHOLDER)) {
            model.vars.removeIf(property -> EMPTY_REQUEST_PLACEHOLDER.equals(property.baseName));
            model.isAlias = false;
            model.isFreeFormObject = false;
        }
        Map<String, String> descriptions = requestFieldDescriptions.get(name);
        if (descriptions != null) {
            model.vars.forEach(property -> {
                String description = descriptions.get(property.baseName);
                if (description != null) {
                    property.description = description;
                    property.unescapedDescription = description;
                }
            });
        }
        if (name.endsWith("OperationRequest")) {
            Set<String> bodyFields = requestBodyFields.getOrDefault(name, Set.of());
            model.vars.stream()
                    .filter(property -> !bodyFields.contains(property.baseName))
                    .forEach(property -> property.vendorExtensions.put("x-sdk-json-ignore", true));
        }
        return model;
    }

    @Override
    public ModelsMap postProcessModels(ModelsMap models) {
        ModelsMap processed = super.postProcessModels(models);
        if (processed.getModels().size() == 1) {
            CodegenModel model = processed.getModels().get(0).getModel();
            Map<String, Object> exception = apiExceptionDefinitions.get(model.name);
            if (exception != null) {
                processed.put("x-sdk-api-exception", true);
                processed.put("exceptionName", model.classname);
                processed.put("errorCode", exception.get("errorCode"));
                processed.put("exceptionDescription", model.description);
                processed.put("hasFields", exception.get("hasFields"));
                processed.put("fields", exception.get("fields"));
            }
            if (model.name.endsWith("OperationRequest")) {
                processed.put("x-sdk-operation-request", true);
            }
        }
        return processed;
    }

    private String moveToExceptionPackage(String filename) {
        String modelPath = modelPackage().replace('.', File.separatorChar);
        String exceptionPath = exceptionPackage.replace('.', File.separatorChar);
        int packageStart = filename.lastIndexOf(modelPath + File.separator);
        if (packageStart < 0) {
            throw new IllegalStateException("Cannot locate model package in generated filename: " + filename);
        }
        return filename.substring(0, packageStart)
                + exceptionPath
                + filename.substring(packageStart + modelPath.length());
    }

    @Override
    public void postProcessModelProperty(CodegenModel model, CodegenProperty property) {
        super.postProcessModelProperty(model, property);
        if (property.pattern != null) {
            property.vendorExtensions.put(
                    "x-sdk-pattern",
                    property.pattern.replace("\"", "\\\""));
        }
    }
}
