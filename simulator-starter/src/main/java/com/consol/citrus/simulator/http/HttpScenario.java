package com.consol.citrus.simulator.http;

import com.consol.citrus.dsl.builder.HttpServerRequestActionBuilder;
import com.consol.citrus.dsl.builder.HttpServerResponseActionBuilder;
import com.consol.citrus.http.message.HttpMessageHeaders;
import com.consol.citrus.simulator.config.SimulatorConfigurationProperties;
import com.consol.citrus.simulator.exception.SimulatorException;
import com.consol.citrus.simulator.scenario.AbstractSimulatorScenario;
import com.consol.citrus.simulator.scenario.ScenarioDesigner;
import io.swagger.models.*;
import io.swagger.models.parameters.*;
import io.swagger.models.properties.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Christoph Deppisch
 */
public class HttpScenario extends AbstractSimulatorScenario {

    /** Operation in wsdl */
    private final Operation operation;

    private final Map<String, Model> definitions;

    /** Base path including context path */
    private final String basePath;

    /** Request path */
    private final String path;

    /** Request method */
    private final HttpMethod method;

    /** Response */
    private Response response;

    /** Response status code */
    private HttpStatus statusCode = HttpStatus.OK;

    /**
     * Default constructor.
     * @param basePath
     * @param path
     * @param method
     * @param operation
     * @param definitions
     * @param simulatorConfiguration
     */
    public HttpScenario(String basePath, String path, HttpMethod method, Operation operation, Map<String, Model> definitions, SimulatorConfigurationProperties simulatorConfiguration) {
        this.operation = operation;
        this.definitions = definitions;
        this.basePath = basePath;
        this.path = path;
        this.method = method;

        if (operation.getResponses() != null) {
            this.response = operation.getResponses().get("200");
        }
    }

    @Override
    public void run(ScenarioDesigner scenario) {
        scenario.echo("Generated scenario from swagger operation: " + operation.getOperationId());

        HttpServerRequestActionBuilder requestBuilder;
        switch (method) {
            case GET:
                requestBuilder = scenario
                    .http()
                    .receive()
                    .get(basePath + path);
                break;
            case POST:
                requestBuilder = scenario
                    .http()
                    .receive()
                    .post(basePath + path);
                break;
            case PUT:
                requestBuilder = scenario
                    .http()
                    .receive()
                    .put(basePath + path);
                break;
            case HEAD:
                requestBuilder = scenario
                    .http()
                    .receive()
                    .head(basePath + path);
                break;
            case DELETE:
                requestBuilder = scenario
                    .http()
                    .receive()
                    .delete(basePath + path);
                break;
            default:
                throw new SimulatorException("Unsupported request method: " + method.name());
        }

        if (operation.getParameters() != null) {
            operation.getParameters().stream()
                    .filter(p -> p instanceof HeaderParameter)
                    .filter(Parameter::getRequired)
                    .forEach(p -> requestBuilder.header(p.getName(), createValidationExpression(((HeaderParameter) p))));

            String queryParams = operation.getParameters().stream()
                    .filter(param -> param instanceof QueryParameter)
                    .filter(Parameter::getRequired)
                    .map(param -> "containsString(" + param.getName() + ")")
                    .collect(Collectors.joining(", "));

            if (StringUtils.hasText(queryParams)) {
                requestBuilder.header(HttpMessageHeaders.HTTP_QUERY_PARAMS, "@assertThat(allOf(" + queryParams + "))@");
            }

            operation.getParameters().stream()
                    .filter(p -> p instanceof BodyParameter)
                    .filter(Parameter::getRequired)
                    .forEach(p -> requestBuilder.payload(createValidationPayload((BodyParameter) p)));
        }

        HttpServerResponseActionBuilder responseBuilder = scenario
            .http()
            .send()
            .response(statusCode)
            .contentType("application/json");

        if (response != null) {
            if (response.getHeaders() != null) {
                for (Map.Entry<String, Property> header : response.getHeaders().entrySet()) {
                    responseBuilder.header(header.getKey(), createRandomValue(header.getValue(), false));
                }
            }

            if (response.getSchema() != null) {
                responseBuilder.payload(createRandomValue(response.getSchema(), false));
            }
        }
    }

    /**
     * Create payload from schema with random values.
     * @param property
     * @param quotes
     * @return
     */
    private String createRandomValue(Property property, boolean quotes) {
        StringBuilder payload = new StringBuilder();
        if (property instanceof RefProperty) {
            Model model = definitions.get(((RefProperty) property).getSimpleRef());
            payload.append("{");

            if (model.getProperties() != null) {
                for (Map.Entry<String, Property> entry : model.getProperties().entrySet()) {
                    payload.append("\"").append(entry.getKey()).append("\": ").append(createRandomValue(entry.getValue(), true)).append(",");
                }
            }

            if (payload.toString().endsWith(",")) {
                payload.replace(payload.length() - 1, payload.length(), "");
            }

            payload.append("}");
        } else if (property instanceof ArrayProperty) {
            payload.append("[");
            payload.append(createRandomValue(((ArrayProperty) property).getItems(), true));
            payload.append("]");
        } else if (property instanceof StringProperty || property instanceof DateProperty || property instanceof DateTimeProperty) {
            if (quotes) {
                payload.append("\"");
            }

            if (property instanceof DateProperty) {
                payload.append("citrus:currentDate()");
            } else if (property instanceof DateTimeProperty) {
                payload.append("citrus:currentDate('yyyy-MM-dd'T'hh:mm:ss')");
            } else if (!CollectionUtils.isEmpty(((StringProperty) property).getEnum())) {
                payload.append("citrus:randomEnumValue(").append(((StringProperty) property).getEnum().stream().map(value -> "'" + value + "'").collect(Collectors.joining(","))).append(")");
            } else {
                payload.append("citrus:randomString(").append(((StringProperty) property).getMaxLength() != null && ((StringProperty) property).getMaxLength() > 0 ? ((StringProperty) property).getMaxLength() : (((StringProperty) property).getMinLength() != null && ((StringProperty) property).getMinLength() > 0 ? ((StringProperty) property).getMinLength() : 10)).append(")");
            }

            if (quotes) {
                payload.append("\"");
            }
        } else if (property instanceof IntegerProperty || property instanceof LongProperty) {
            payload.append("citrus:randomNumber(10)");
        }  else if (property instanceof FloatProperty || property instanceof DoubleProperty) {
            payload.append("citrus:randomNumber(10)");
        } else if (property instanceof BooleanProperty) {
            payload.append("citrus:randomEnumValue('true', 'false')");
        } else {
            if (quotes) {
                payload.append("\"\"");
            } else {
                payload.append("");
            }
        }

        return payload.toString();
    }

    /**
     * Creates control payload for validation.
     * @param parameter
     * @return
     */
    private String createValidationPayload(BodyParameter parameter) {
        StringBuilder payload = new StringBuilder();

        Model model = parameter.getSchema();

        if (model instanceof RefModel) {
            model = definitions.get(((RefModel) model).getSimpleRef());
        }

        if (model instanceof ArrayModel) {
            payload.append("[");
            payload.append(createValidationExpression(((ArrayModel) model).getItems()));
            payload.append("]");
        } else {

            payload.append("{");

            if (model.getProperties() != null) {
                for (Map.Entry<String, Property> entry : model.getProperties().entrySet()) {
                    payload.append("\"").append(entry.getKey()).append("\": ").append(createValidationExpression(entry.getValue())).append(",");
                }
            }

            if (payload.toString().endsWith(",")) {
                payload.replace(payload.length() - 1, payload.length(), "");
            }

            payload.append("}");
        }

        return payload.toString();
    }

    /**
     * Create validation expression using functions according to parameter type and format.
     * @param property
     * @return
     */
    private String createValidationExpression(Property property) {
        StringBuilder payload = new StringBuilder();
        if (property instanceof RefProperty) {
            Model model = definitions.get(((RefProperty) property).getSimpleRef());
            payload.append("{");

            if (model.getProperties() != null) {
                for (Map.Entry<String, Property> entry : model.getProperties().entrySet()) {
                    payload.append("\"").append(entry.getKey()).append("\": ").append(createValidationExpression(entry.getValue())).append(",");
                }
            }

            if (payload.toString().endsWith(",")) {
                payload.replace(payload.length() - 1, payload.length(), "");
            }

            payload.append("}");
        } else if (property instanceof ArrayProperty) {
            payload.append("\"@ignore@\"");
        } else if (property instanceof StringProperty) {
            if (StringUtils.hasText(((StringProperty) property).getPattern())) {
                payload.append("\"@matches(").append(((StringProperty) property).getPattern()).append(")@\"");
            } else if (!CollectionUtils.isEmpty(((StringProperty) property).getEnum())) {
                payload.append("\"@matches(").append(((StringProperty) property).getEnum().stream().collect(Collectors.joining("|"))).append(")@\"");
            } else {
                payload.append("\"@notEmpty()@\"");
            }
        } else if (property instanceof DateProperty) {
            payload.append("\"@matchesDatePattern('yyyy-MM-dd')@\"");
        } else if (property instanceof DateTimeProperty) {
            payload.append("\"@matchesDatePattern('yyyy-MM-dd'T'hh:mm:ss')@\"");
        } else if (property instanceof IntegerProperty || property instanceof LongProperty) {
            payload.append("\"@isNumber()@\"");
        } else if (property instanceof FloatProperty || property instanceof DoubleProperty) {
            payload.append("\"@isNumber()@\"");
        } else if (property instanceof BooleanProperty) {
            payload.append("\"@matches(true|false)@\"");
        } else {
            payload.append("\"@ignore@\"");
        }

        return payload.toString();
    }

    /**
     * Create validation expression using functions according to parameter type and format.
     * @param parameter
     * @return
     */
    private String createValidationExpression(AbstractSerializableParameter parameter) {
        switch (parameter.getType()) {
            case "integer":
                return "@isNumber()@";
            case "string":
                if (parameter.getFormat() != null && parameter.getFormat().equals("date")) {
                    return "\"@matchesDatePattern('yyyy-MM-dd')@\"";
                } else if (parameter.getFormat() != null && parameter.getFormat().equals("date-time")) {
                    return "\"@matchesDatePattern('yyyy-MM-dd'T'hh:mm:ss')@\"";
                } else if (StringUtils.hasText(parameter.getPattern())) {
                    return "\"@matches(" + parameter.getPattern() + ")@\"";
                } else if (!CollectionUtils.isEmpty(parameter.getEnum())) {
                    return "\"@matches(" + (parameter.getEnum().stream().collect(Collectors.joining("|"))) + ")@\"";
                } else {
                    return "@notEmpty()@";
                }
            case "boolean":
                return "@matches(true|false)@";
            default:
                return "@ignore@";
        }
    }

    /**
     * Gets the operation.
     *
     * @return
     */
    public Operation getOperation() {
        return operation;
    }

    /**
     * Gets the path.
     *
     * @return
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets the method.
     *
     * @return
     */
    public HttpMethod getMethod() {
        return method;
    }

    /**
     * Gets the response.
     *
     * @return
     */
    public Response getResponse() {
        return response;
    }

    /**
     * Sets the response.
     *
     * @param response
     */
    public void setResponse(Response response) {
        this.response = response;
    }

    /**
     * Gets the statusCode.
     *
     * @return
     */
    public HttpStatus getStatusCode() {
        return statusCode;
    }

    /**
     * Sets the statusCode.
     *
     * @param statusCode
     */
    public void setStatusCode(HttpStatus statusCode) {
        this.statusCode = statusCode;
    }
}