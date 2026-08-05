package cloud.omas.sdk.generator.go;

import cloud.omas.sdk.generator.common.ApiErrorCatalog;
import cloud.omas.sdk.generator.common.ApiErrorDefinition;
import cloud.omas.sdk.generator.common.OperationRequestDefinition;
import cloud.omas.sdk.generator.common.OperationRequestFlattener;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.GoClientCodegen;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.utils.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OmasGoClientCodegen extends GoClientCodegen {
    private static final String REQUEST_CLASS = "x-sdk-request-class";
    private static final String JSON_IGNORE = "x-sdk-json-ignore";

    private String serviceName = "service";
    private final Map<String, Map<String, String>> requestFieldDescriptions = new HashMap<>();
    private final Map<String, Set<String>> requestBodyFields = new HashMap<>();

    public OmasGoClientCodegen() {
        super();
        templateDir = "omas-go";
        embeddedTemplateDir = "go";
        apiTemplateFiles.clear();
        apiTemplateFiles.put("api.mustache", ".go");
        supportingFiles.clear();
        cliOptions.add(CliOption.newString("serviceName", "Product service name used for the generated client."));
    }

    @Override
    public String getName() {
        return "omas-go";
    }

    @Override
    public String getHelp() {
        return "Generates Omas Go service clients and models.";
    }

    @Override
    public void processOpts() {
        super.processOpts();
        serviceName = String.valueOf(additionalProperties.getOrDefault("serviceName", "service"));
        additionalProperties.put("serviceName", serviceName);
        additionalProperties.put("clientClass", "Client");
        supportingFiles.clear();
        supportingFiles.add(new SupportingFile("api_errors.mustache", "", "api_errors.go"));
    }

    @Override
    public void preprocessOpenAPI(OpenAPI openAPI) {
        configureErrorResponseMappings(openAPI);
        configureServiceErrors(openAPI);
        if (openAPI.getPaths() != null) {
            openAPI.getPaths().values().stream()
                    .flatMap(pathItem -> pathItem.readOperations().stream())
                    .forEach(operation -> operation.addExtension(
                            "x-sdk-operation-id", operation.getOperationId()));
        }
        super.preprocessOpenAPI(openAPI);
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            additionalProperties.put("serverUrl", openAPI.getServers().get(0).getUrl());
        }
        if (openAPI.getPaths() == null) {
            return;
        }
        openAPI.getPaths().forEach((path, pathItem) -> operations(pathItem).forEach(operation -> {
            operation.setTags(List.of(serviceName));
            if (operation.getParameters() != null) {
                operation.getParameters().forEach(parameter -> parameter.addExtension(
                        "x-sdk-field-name", toVarName(parameter.getName())));
            }
            OperationRequestDefinition request = OperationRequestFlattener.flatten(openAPI, operation);
            request.schema().addExtension("x-sdk-operation-request", true);
            if (request.schema().getProperties() != null) {
                request.schema().getProperties().forEach((name, property) -> {
                    if (!request.bodyFields().contains(name)) {
                        ((Schema<?>) property).addExtension(JSON_IGNORE, true);
                    }
                });
            }
            operation.addExtension(REQUEST_CLASS, request.schemaName());
            requestFieldDescriptions.put(request.schemaName(), request.parameterDescriptions());
            requestBodyFields.put(request.schemaName(), request.bodyFields());
            openAPI.getComponents().addSchemas(request.schemaName(), request.schema());
        }));
    }

    private void configureErrorResponseMappings(OpenAPI openAPI) {
        ApiErrorCatalog.errorResponseSchemaNames(openAPI)
                .forEach(name -> schemaMapping.put(name, "map[string]interface{}"));
    }

    private void configureServiceErrors(OpenAPI openAPI) {
        List<Map<String, Object>> errors = new ArrayList<>();
        ApiErrorCatalog.discover(openAPI).forEach(definition -> errors.add(serviceError(definition)));
        additionalProperties.put("serviceErrors", errors);
        additionalProperties.put("hasServiceErrors", !errors.isEmpty());
        additionalProperties.put("hasServiceErrorDetails", errors.stream()
                .anyMatch(error -> Boolean.TRUE.equals(error.get("hasDetails"))));
    }

    private Map<String, Object> serviceError(ApiErrorDefinition error) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schemaName", error.schemaName());
        definition.put("type", error.schemaName().substring(
                0,
                error.schemaName().length() - "Exception".length()) + "Error");
        definition.put("errorCode", error.errorCode());
        definition.put("hasDetails", error.detailsSchemaName() != null);
        definition.put("detailsType", error.detailsSchemaName());
        return definition;
    }

    private List<Operation> operations(PathItem pathItem) {
        return pathItem.readOperations();
    }

    @Override
    public CodegenModel fromModel(String name, Schema schema) {
        CodegenModel model = super.fromModel(name, schema);
        if (name.endsWith("OperationRequest")) {
            model.vendorExtensions.put("x-sdk-operation-request", true);
            if (schema.getProperties() != null
                    && schema.getProperties().containsKey(OperationRequestFlattener.EMPTY_REQUEST_PLACEHOLDER)) {
                model.vars.removeIf(property -> OperationRequestFlattener.EMPTY_REQUEST_PLACEHOLDER
                        .equals(property.baseName));
                model.isAlias = false;
                model.isFreeFormObject = false;
            }
            Set<String> bodyFields = requestBodyFields.getOrDefault(name, Set.of());
            Map<String, String> descriptions = requestFieldDescriptions.getOrDefault(name, Map.of());
            for (CodegenProperty property : model.vars) {
                if (!bodyFields.contains(property.baseName)) {
                    property.vendorExtensions.put(JSON_IGNORE, true);
                }
                String description = descriptions.get(property.baseName);
                if (description != null) {
                    property.description = description;
                    property.unescapedDescription = description;
                }
            }
            model.vendorExtensions.put("x-sdk-has-validation-rules", model.vars.stream()
                    .anyMatch(this::hasValidationRule));
            model.vendorExtensions.put("x-sdk-has-required-string", model.vars.stream()
                    .anyMatch(property -> property.required && property.isString));
            model.vendorExtensions.put("x-sdk-has-pattern", model.vars.stream()
                    .anyMatch(property -> property.pattern != null));
            model.vendorExtensions.put("x-sdk-has-unique-items", model.vars.stream()
                    .anyMatch(CodegenProperty::getUniqueItems));
        }
        return model;
    }

    private boolean hasValidationRule(CodegenProperty property) {
        return (property.required && (property.isString || property.isArray || property.isMap))
                || property.minLength != null
                || property.maxLength != null
                || property.pattern != null
                || property.minimum != null
                || property.maximum != null
                || property.minItems != null
                || property.maxItems != null
                || property.getUniqueItems();
    }

    @Override
    public void postProcessModelProperty(CodegenModel model, CodegenProperty property) {
        super.postProcessModelProperty(model, property);
        if (property.pattern != null) {
            property.vendorExtensions.put(
                    "x-sdk-pattern",
                    property.pattern.replace("\\", "\\\\").replace("\"", "\\\""));
        }
    }

    @Override
    public String toVarName(String name) {
        return super.toVarName(name).replaceAll("Id(?=$|[A-Z])", "ID");
    }

    @Override
    public ModelsMap postProcessModels(ModelsMap models) {
        ModelsMap processed = super.postProcessModels(models);
        if (processed.getModels().size() == 1) {
            CodegenModel model = processed.getModels().get(0).getModel();
            if (model.name.endsWith("OperationRequest")) {
                processed.put("x-sdk-operation-request", true);
            }
        }
        return processed;
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
        return "Client";
    }

    @Override
    public String apiFilename(String templateName, String tag) {
        return apiFileFolder() + File.separator + StringUtils.underscore(serviceName) + "_client.go";
    }
}
