/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.entrypoint.mcp.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.http.MediaType;
import io.gravitee.common.util.MultiValueMap;
import io.gravitee.entrypoint.mcp.configuration.MCPEntrypointConnectorConfiguration;
import io.gravitee.entrypoint.mcp.configuration.MCPGatewayMappingHttp;
import io.gravitee.entrypoint.mcp.model.call.JsonRPCCallRequest;
import io.gravitee.entrypoint.mcp.model.call.JsonRPCCallRequestParams;
import io.gravitee.entrypoint.mcp.model.call.JsonRPCCallResponse;
import io.gravitee.entrypoint.mcp.model.call.JsonRPCCallResponseResults;
import io.gravitee.entrypoint.mcp.model.call.JsonRPCCallResponseResultsContent;
import io.gravitee.entrypoint.mcp.model.errors.JsonRPCResponseError;
import io.gravitee.entrypoint.mcp.model.errors.McpErrorCodes;
import io.gravitee.entrypoint.mcp.model.initialize.JsonRPCInitializeResponse;
import io.gravitee.entrypoint.mcp.model.initialize.JsonRPCInitializeResponseResults;
import io.gravitee.entrypoint.mcp.model.initialize.JsonRPCInitializeResponseServerInfo;
import io.gravitee.entrypoint.mcp.model.list.JsonRPCListResponse;
import io.gravitee.entrypoint.mcp.model.list.JsonRPCListResponseResults;
import io.gravitee.entrypoint.mcp.model.list.JsonRPCListResponseResultsTool;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.context.InternalContextAttributes;
import io.gravitee.gateway.reactive.api.context.http.HttpExecutionContext;
import io.gravitee.gateway.reactive.core.context.DefaultExecutionContext;
import io.gravitee.gateway.reactive.core.context.MutableRequest;
import io.gravitee.gateway.reactive.handlers.api.v4.Api;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MCPHandler {

    static final String ATTR_INTERNAL_MCP_METHOD = "mcp.method";
    static final String ATTR_INTERNAL_MCP_SESSION_ID = "mcp.session.id";
    static final String ATTR_INTERNAL_MCP_REQUEST_ID = "mcp.request.id";
    static final String ATTR_INTERNAL_MCP_ERROR_INVALID_REQUEST = "mcp.error.invalid_request";
    static final String ATTR_INTERNAL_MCP_ERROR_PARSE_ERROR = "mcp.error.parse_error";
    static final String ATTR_INTERNAL_MCP_ERROR_INTERNAL_ERROR = "mcp.error.internal_error";
    private final ObjectMapper mapper;
    private final MCPEntrypointConnectorConfiguration configuration;
    private final List<JsonRPCListResponseResultsTool> tools;

    public MCPHandler(MCPEntrypointConnectorConfiguration configuration) {
        this.configuration = configuration;
        this.mapper = new ObjectMapper();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.tools =
            this.configuration.getTools()
                .stream()
                .map(mcpTool ->
                    JsonRPCListResponseResultsTool
                        .builder()
                        .name(mcpTool.getToolDefinition().getName())
                        .description(mcpTool.getToolDefinition().getDescription())
                        .inputSchema(mcpTool.getToolDefinition().getInputSchema())
                        .build()
                )
                .toList();
    }

    // HANDLE REQUEST
    public Completable handleRequest(HttpExecutionContext ctx) {
        MultiValueMap<String, String> parameters = ctx.request().parameters();
        ctx.setInternalAttribute(ATTR_INTERNAL_MCP_SESSION_ID, parameters.getFirst("sessionId"));

        // By Default invoker is skipped and will be enabled only for tools/call
        ctx.setInternalAttribute(InternalContextAttributes.ATTR_INTERNAL_INVOKER_SKIP, Boolean.TRUE);

        return Completable.fromMaybe(
            ctx
                .request()
                .body()
                .doOnEvent((buffer, throwable) -> {
                    if (throwable != null) {
                        log.error(throwable.getMessage(), throwable);
                        ctx.setInternalAttribute(ATTR_INTERNAL_MCP_ERROR_INTERNAL_ERROR, Boolean.TRUE);
                    } else if (buffer == null) {
                        ctx.setInternalAttribute(ATTR_INTERNAL_MCP_ERROR_PARSE_ERROR, Boolean.TRUE);
                    } else {
                        try {
                            JsonNode jsonNode = mapper.readTree(buffer.getBytes());
                            JsonNode idNode = jsonNode.get("id");
                            if (jsonNode.get("jsonrpc") == null || idNode == null) {
                                ctx.setInternalAttribute(ATTR_INTERNAL_MCP_ERROR_INVALID_REQUEST, Boolean.TRUE);
                            } else {
                                String mcpMethod = jsonNode.get("method").asText();
                                ctx.setInternalAttribute(ATTR_INTERNAL_MCP_METHOD, mcpMethod);
                                ctx.setInternalAttribute(ATTR_INTERNAL_MCP_REQUEST_ID, idNode.asInt());

                                log.debug("Handling request for method {}", mcpMethod);
                                if (mcpMethod.equals("tools/call")) {
                                    log.debug("Enable invocation of the API");
                                    ctx.setInternalAttribute(InternalContextAttributes.ATTR_INTERNAL_INVOKER_SKIP, Boolean.FALSE);

                                    log.debug("Preparing call to the endpoint");
                                    prepareToolCallRequest(ctx, mapper.convertValue(jsonNode, JsonRPCCallRequest.class));
                                }
                            }
                        } catch (IOException ex) {
                            ctx.setInternalAttribute(ATTR_INTERNAL_MCP_ERROR_PARSE_ERROR, Boolean.TRUE);
                        }
                    }
                })
        );
    }

    private void prepareToolCallRequest(HttpExecutionContext ctx, JsonRPCCallRequest jsonRPCCallRequest) throws Exception {
        JsonRPCCallRequestParams jsonRPCCallRequestParams = jsonRPCCallRequest.getParams();
        MCPGatewayMappingHttp mcpGatewayMappingHttp =
            this.configuration.getTools()
                .stream()
                .filter(mcpTool -> mcpTool.getToolDefinition().getName().equals(jsonRPCCallRequestParams.getName()))
                .map(mcpTool -> mcpTool.getGatewayMapping().getHttp())
                .filter(Objects::nonNull)
                .findFirst()
                // TODO: manage this exception properly
                .orElseThrow(() -> new Exception("tool not found"));

        log.debug("MCPGatewayMapping: {}", mcpGatewayMappingHttp);

        MutableRequest mutableRequest = ((DefaultExecutionContext) ctx).request();
        mutableRequest.method(HttpMethod.valueOf(mcpGatewayMappingHttp.getMethod()));

        String builtPath = buildPath(jsonRPCCallRequestParams.getArguments(), mcpGatewayMappingHttp);

        log.debug("BuiltPath: {}", builtPath);

        mutableRequest.pathInfo(builtPath);

        updateRequestHeaders(mutableRequest.headers(), jsonRPCCallRequestParams.getArguments(), mcpGatewayMappingHttp.getHeaders());

        if (jsonRPCCallRequestParams.getArguments().get("bodySchema") != null) {
            log.debug("adding body");

            mutableRequest.headers().set(HttpHeaderNames.ACCEPT, "application/json");
            mutableRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, mcpGatewayMappingHttp.getContentType());
            mutableRequest.body(Buffer.buffer(mapper.writeValueAsString(jsonRPCCallRequestParams.getArguments().get("bodySchema"))));
        }
    }

    private String buildPath(Map<String, Object> arguments, MCPGatewayMappingHttp mcpGatewayMapping) {
        String path = mcpGatewayMapping.getPath();
        for (String pathParam : mcpGatewayMapping.getPathParams()) {
            path = path.replace(":" + pathParam, arguments.get(pathParam).toString());
        }

        Optional<String> queryParams = mcpGatewayMapping
            .getQueryParams()
            .stream()
            .filter(arguments::containsKey)
            .map(s -> s + "=" + arguments.get(s).toString())
            .reduce((s, s2) -> s + "&" + s2);
        if (queryParams.isPresent()) {
            path = path + "?" + queryParams.get();
        }

        return path;
    }

    private void updateRequestHeaders(HttpHeaders requestHeaders, Map<String, Object> arguments, List<String> mcpGatewayMappingHeaders) {
        mcpGatewayMappingHeaders.forEach(h -> requestHeaders.set(h, arguments.get(h).toString()));
    }

    // HANDLE RESPONSE
    public Completable handleResponse(HttpExecutionContext ctx) {
        return Maybe
            .defer(() -> {
                Boolean isInternalError = ctx.getInternalAttribute(ATTR_INTERNAL_MCP_ERROR_INTERNAL_ERROR);
                if (isInternalError != null && isInternalError) {
                    return Maybe.just(internalError());
                }

                Boolean isParseError = ctx.getInternalAttribute(ATTR_INTERNAL_MCP_ERROR_PARSE_ERROR);
                if (isParseError != null && isParseError) {
                    return Maybe.just(parseError());
                }

                Boolean isInvalidRequest = ctx.getInternalAttribute(ATTR_INTERNAL_MCP_ERROR_INVALID_REQUEST);
                if (isInvalidRequest != null && isInvalidRequest) {
                    return Maybe.just(invalidRequest());
                }

                String mcpMethod = ctx.getInternalAttribute(ATTR_INTERNAL_MCP_METHOD);
                Integer jsonRequestId = ctx.getInternalAttribute(ATTR_INTERNAL_MCP_REQUEST_ID);
                String sessionId = ctx.getInternalAttribute(ATTR_INTERNAL_MCP_SESSION_ID);
                ctx.removeInternalAttribute(ATTR_INTERNAL_MCP_METHOD);
                ctx.removeInternalAttribute(ATTR_INTERNAL_MCP_REQUEST_ID);
                ctx.removeInternalAttribute(ATTR_INTERNAL_MCP_SESSION_ID);

                log.debug(
                    "Received POST response for MCP with method: {}, request id: {} and session id: {}",
                    mcpMethod,
                    jsonRequestId,
                    sessionId
                );

                if (mcpMethod.equals("tools/call")) {
                    return ctx.response().body().map(body -> formatToolResponse(jsonRequestId, body));
                } else {
                    byte[] data =
                        switch (mcpMethod) {
                            case "initialize" -> {
                                Api api = ctx.getComponent(Api.class);
                                yield initialize(jsonRequestId, api.getName(), api.getApiVersion());
                            }
                            case "tools/list" -> listTools(jsonRequestId, this.tools);
                            case "notifications/cancelled", "notifications/initialized" -> new byte[0];
                            default -> notSupportedMethod(jsonRequestId, mcpMethod);
                        };
                    return Maybe.just(data);
                }
            })
            .onErrorResumeNext(throwable -> {
                log.error(throwable.getMessage(), throwable);
                return Maybe.just(internalError());
            })
            .flatMapCompletable(data -> {
                if (data.length != 0) {
                    Buffer buffer = Buffer.buffer(data);
                    log.debug("Sending buffer: {}", buffer);
                    ctx.response().headers().set(HttpHeaderNames.CONTENT_TYPE, MediaType.APPLICATION_JSON);
                    ctx.response().headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.length() + "");
                    ctx.response().status(HttpResponseStatus.OK.code());
                    ctx.response().body(buffer);
                }
                return Completable.complete();
            });
    }

    private byte[] initialize(Integer jsonRequestId, String apiName, String apiVersion) throws JsonProcessingException {
        JsonRPCInitializeResponse initializeResponse = new JsonRPCInitializeResponse();
        initializeResponse.setId(jsonRequestId);

        JsonRPCInitializeResponseResults responseResults = new JsonRPCInitializeResponseResults();
        responseResults.setCapabilities(Map.of("tools", Map.of()));
        responseResults.setServerInfo(JsonRPCInitializeResponseServerInfo.builder().name(apiName).version(apiVersion).build());
        initializeResponse.setResult(responseResults);
        log.debug("Initialize response: {}", initializeResponse);

        return mapper.writeValueAsString(initializeResponse).getBytes();
    }

    private byte[] listTools(Integer jsonRequestId, List<JsonRPCListResponseResultsTool> tools) throws JsonProcessingException {
        JsonRPCListResponse listResponse = new JsonRPCListResponse();
        listResponse.setId(jsonRequestId);

        JsonRPCListResponseResults responseResults = new JsonRPCListResponseResults();
        responseResults.setTools(tools);
        listResponse.setResult(responseResults);

        log.debug("Tools/list response: {}", listResponse);

        return mapper.writeValueAsString(listResponse).getBytes();
    }

    private byte[] formatToolResponse(Integer jsonRequestId, Buffer buffer) throws JsonProcessingException {
        JsonRPCCallResponse callResponse = new JsonRPCCallResponse();
        callResponse.setId(jsonRequestId);
        JsonRPCCallResponseResults jsonRPCCallResponseResults = new JsonRPCCallResponseResults();
        jsonRPCCallResponseResults.setContent(
            List.of(JsonRPCCallResponseResultsContent.builder().type("text").text(buffer.toString()).build())
        );
        callResponse.setResult(jsonRPCCallResponseResults);
        log.debug("Tools/call response: {}", callResponse);

        return mapper.writeValueAsString(callResponse).getBytes();
    }

    private byte[] notSupportedMethod(Integer jsonRequestId, String method) throws JsonProcessingException {
        return mapper
            .writeValueAsString(JsonRPCResponseError.newError(jsonRequestId, McpErrorCodes.METHOD_NOT_FOUND, "Method not found: " + method))
            .getBytes();
    }

    private byte[] invalidRequest() throws JsonProcessingException {
        return mapper
            .writeValueAsString(JsonRPCResponseError.newError(-1, McpErrorCodes.INVALID_REQUEST, "Json is not a valid request"))
            .getBytes();
    }

    private byte[] parseError() throws JsonProcessingException {
        return mapper.writeValueAsString(JsonRPCResponseError.newError(-1, McpErrorCodes.PARSE_ERROR, "Json body is not valid")).getBytes();
    }

    private byte[] internalError() throws JsonProcessingException {
        return mapper
            .writeValueAsString(JsonRPCResponseError.newError(-1, McpErrorCodes.INTERNAL_ERROR, "Error occurred during request handling"))
            .getBytes();
    }
}
