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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.http.MediaType;
import io.gravitee.common.util.LinkedMultiValueMap;
import io.gravitee.entrypoint.mcp.configuration.MCPEntrypointConnectorConfiguration;
import io.gravitee.entrypoint.mcp.configuration.MCPGatewayMapping;
import io.gravitee.entrypoint.mcp.configuration.MCPGatewayMappingHttp;
import io.gravitee.entrypoint.mcp.configuration.MCPTool;
import io.gravitee.entrypoint.mcp.configuration.MCPToolAnnotations;
import io.gravitee.entrypoint.mcp.configuration.MCPToolDefinition;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.core.component.CustomComponentProvider;
import io.gravitee.gateway.reactive.api.context.ContextAttributes;
import io.gravitee.gateway.reactive.api.context.InternalContextAttributes;
import io.gravitee.gateway.reactive.core.context.DefaultExecutionContext;
import io.gravitee.gateway.reactive.core.context.MutableRequest;
import io.gravitee.gateway.reactive.core.context.MutableResponse;
import io.gravitee.gateway.reactive.handlers.api.v4.Api;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MCPHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private MCPHandler cut;

    private DefaultExecutionContext ctx;
    private HttpHeaders requestHeaders;
    private HttpHeaders responseHeaders;

    @Mock
    private MutableRequest request;

    @Mock
    private MutableResponse response;

    @BeforeEach
    void beforeEach() throws JsonProcessingException {
        requestHeaders = HttpHeaders.create();
        lenient().when(request.headers()).thenReturn(requestHeaders);
        lenient().when(request.parameters()).thenReturn(new LinkedMultiValueMap<>(Map.of("sessionId", List.of("123-456-789"))));
        lenient().when(request.body()).thenReturn(Maybe.empty());

        responseHeaders = HttpHeaders.create();
        lenient().when(response.headers()).thenReturn(responseHeaders);
        lenient().when(response.body()).thenReturn(Maybe.empty());

        List<MCPTool> tools = List.of(
            MCPTool.builder()
                .toolDefinition(
                    MCPToolDefinition.builder()
                        .name("ToolName")
                        .description("ToolDescription")
                        .inputSchema(mapper.readTree("{}"))
                        .annotations(new MCPToolAnnotations("My tool", true, false, true, false))
                        .build()
                )
                .gatewayMapping(
                    MCPGatewayMapping.builder()
                        .http(
                            MCPGatewayMappingHttp.builder()
                                .method("POST")
                                .path("/foo/:myPathParam/bar/:anotherParam")
                                .contentType("application/json")
                                .headers(List.of("X-My-Header"))
                                .pathParams(List.of("myPathParam", "anotherParam"))
                                .queryParams(List.of("myQueryParam", "myQueryParam2", "myQueryParam3", "myQueryParam4"))
                                .build()
                        )
                        .build()
                )
                .build()
        );
        MCPEntrypointConnectorConfiguration cutConfiguration = new MCPEntrypointConnectorConfiguration();
        cutConfiguration.setTools(tools);
        cut = new MCPHandler(cutConfiguration);
        ctx = new DefaultExecutionContext(request, response);
        ctx.setAttribute(ContextAttributes.ATTR_CONTEXT_PATH, "/contextPath");
    }

    @Nested
    class InitializeRequest {

        @BeforeEach
        void beforeEach() {
            lenient()
                .when(request.body())
                .thenReturn(
                    Maybe.just(
                        Buffer.buffer(
                            """
                            {
                               "jsonrpc": "2.0",
                               "id": 1,
                               "method": "initialize",
                               "params": {
                                 "protocolVersion": "2025-03-26",
                                 "capabilities": {
                                   "roots": {
                                     "listChanged": true
                                   },
                                   "sampling": {}
                                 },
                                 "clientInfo": {
                                   "name": "ExampleClient",
                                   "version": "1.0.0"
                                 }
                               }
                             }"""
                        )
                    )
                );
        }

        @Test
        void shouldHandleInitializeRequest() {
            cut.handleRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isEqualTo("123-456-789");
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isEqualTo("initialize");
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isEqualTo(1);

            assertThat((Boolean) ctx.getInternalAttribute(InternalContextAttributes.ATTR_INTERNAL_INVOKER_SKIP)).isTrue();
        }

        @Test
        void shouldHandleInitializeResponse() {
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID, "123-456-789");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD, "initialize");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID, 1);
            Api api = new Api(io.gravitee.definition.model.v4.Api.builder().name("ExampleApi").apiVersion("1.0.0").build());
            CustomComponentProvider customComponentProvider = new CustomComponentProvider();
            customComponentProvider.add(Api.class, api);
            ctx.componentProvider(customComponentProvider);

            cut.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("147");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"ExampleApi\",\"version\":\"1.0.0\"}}}"
                        )
                )
            );
        }
    }

    @Nested
    class ToolsListRequest {

        @BeforeEach
        void beforeEach() {
            lenient()
                .when(request.body())
                .thenReturn(
                    Maybe.just(
                        Buffer.buffer(
                            """
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "method": "tools/list",
                              "params": {
                                "cursor": "optional-cursor-value"
                              }
                            }"""
                        )
                    )
                );
        }

        @Test
        void shouldHandleToolsListRequest() {
            cut.handleRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isEqualTo("123-456-789");
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isEqualTo("tools/list");
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isEqualTo(1);

            assertThat((Boolean) ctx.getInternalAttribute(InternalContextAttributes.ATTR_INTERNAL_INVOKER_SKIP)).isTrue();
        }

        @Test
        void shouldHandleToolsListResponse() {
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID, "123-456-789");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD, "tools/list");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID, 1);

            cut.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("236");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"ToolName\",\"description\":\"ToolDescription\",\"inputSchema\":{},\"annotations\":{\"title\":\"My tool\",\"readOnlyHint\":true,\"destructiveHint\":false,\"idempotentHint\":true,\"openWorldHint\":false}}]}}"
                        )
                )
            );
        }

        @Test
        void shouldHandleToolsListResponseWithOutputSchema() throws JsonProcessingException {
            // Create a new handler with a tool that has outputSchema
            List<MCPTool> toolsWithOutputSchema = List.of(
                MCPTool.builder()
                    .toolDefinition(
                        MCPToolDefinition.builder()
                            .name("ToolWithOutputSchema")
                            .description("Tool with output schema")
                            .inputSchema(mapper.readTree("{}"))
                            .outputSchema(mapper.readTree("{\"type\":\"object\"}"))
                            .build()
                    )
                    .gatewayMapping(
                        MCPGatewayMapping.builder()
                            .http(
                                MCPGatewayMappingHttp.builder()
                                    .method("GET")
                                    .path("/test")
                                    .contentType("application/json")
                                    .headers(List.of())
                                    .pathParams(List.of())
                                    .queryParams(List.of())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            );
            MCPEntrypointConnectorConfiguration configWithOutputSchema = new MCPEntrypointConnectorConfiguration();
            configWithOutputSchema.setTools(toolsWithOutputSchema);
            MCPHandler handlerWithOutputSchema = new MCPHandler(configWithOutputSchema);

            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID, "123-456-789");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD, "tools/list");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID, 1);

            handlerWithOutputSchema.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"ToolWithOutputSchema\",\"description\":\"Tool with output schema\",\"inputSchema\":{},\"outputSchema\":{\"type\":\"object\"}}]}}"
                        )
                )
            );
        }
    }

    @Nested
    class ToolsCallRequest {

        @BeforeEach
        void beforeEach() {
            lenient()
                .when(request.body())
                .thenReturn(
                    Maybe.just(
                        Buffer.buffer(
                            """
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "method": "tools/call",
                              "params": {
                                "name": "ToolName",
                                "arguments": {
                                  "X-My-Header": "headerValue",
                                  "myPathParam": "pathParam1",
                                  "anotherParam": "pathParam2",
                                  "myQueryParam": "queryValue",
                                  "myQueryParam2": ["value1", "value2"],
                                  "myQueryParam3": [],
                                  "myQueryParam4": "query with space",
                                  "bodySchema": {
                                    "type": "string"
                                  }
                                }
                              }
                            }"""
                        )
                    )
                );
        }

        @Test
        void shouldHandleToolsCallRequest() {
            cut.handleRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isEqualTo("123-456-789");
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isEqualTo("tools/call");
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isEqualTo(2);

            assertThat((Boolean) ctx.getInternalAttribute(InternalContextAttributes.ATTR_INTERNAL_INVOKER_SKIP)).isFalse();

            String sentBuffer = "{\"type\":\"string\"}";
            assertThat(requestHeaders.get("X-My-Header")).isEqualTo("headerValue");
            assertThat(requestHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("application/json");
            assertThat(requestHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(String.valueOf(sentBuffer.getBytes().length));
            assertThat(requestHeaders.get(HttpHeaderNames.ACCEPT)).isEqualTo("application/json");
            verify(request).method(HttpMethod.POST);
            verify(request).pathInfo(
                "/foo/pathParam1/bar/pathParam2?myQueryParam=queryValue&myQueryParam2=value1&myQueryParam2=value2&myQueryParam4=query%20with%20space"
            );
            verify(request).body(argThat(buffer -> buffer.toString().equals(sentBuffer)));
        }

        @Test
        void shouldHandleToolsCallRequestWhenToolPathIsSameAsAPIContextPath() {
            ctx.setAttribute(ContextAttributes.ATTR_CONTEXT_PATH, "/foo");

            cut.handleRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
            verify(request).pathInfo(
                "/pathParam1/bar/pathParam2?myQueryParam=queryValue&myQueryParam2=value1&myQueryParam2=value2&myQueryParam4=query%20with%20space"
            );
        }

        @Test
        void shouldHandleToolsCallResponseWithoutOutputSchema() {
            // Tool does not have outputSchema, so response should NOT be wrapped in bodySchema
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID, "123-456-789");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD, "tools/call");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID, 1);
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_TOOL_NAME, "ToolName");

            when(response.body()).thenReturn(Maybe.just(Buffer.buffer("{\"foo\":\"bar\"}")));

            cut.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_TOOL_NAME)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("104");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"foo\\\":\\\"bar\\\"}\"}],\"error\":false}}"
                        )
                )
            );
        }

        @Test
        void shouldHandleToolsCallResponseWithOutputSchema() throws JsonProcessingException {
            // Create a new handler with a tool that has outputSchema
            List<MCPTool> toolsWithOutputSchema = List.of(
                MCPTool.builder()
                    .toolDefinition(
                        MCPToolDefinition.builder()
                            .name("ToolWithOutputSchema")
                            .description("Tool with output schema")
                            .inputSchema(mapper.readTree("{}"))
                            .outputSchema(mapper.readTree("{\"type\":\"object\"}"))
                            .build()
                    )
                    .gatewayMapping(
                        MCPGatewayMapping.builder()
                            .http(
                                MCPGatewayMappingHttp.builder()
                                    .method("GET")
                                    .path("/test")
                                    .contentType("application/json")
                                    .headers(List.of())
                                    .pathParams(List.of())
                                    .queryParams(List.of())
                                    .build()
                            )
                            .build()
                    )
                    .build()
            );
            MCPEntrypointConnectorConfiguration configWithOutputSchema = new MCPEntrypointConnectorConfiguration();
            configWithOutputSchema.setTools(toolsWithOutputSchema);
            MCPHandler handlerWithOutputSchema = new MCPHandler(configWithOutputSchema);

            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID, "123-456-789");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD, "tools/call");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID, 1);
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_TOOL_NAME, "ToolWithOutputSchema");

            when(response.body()).thenReturn(Maybe.just(Buffer.buffer("{\"foo\":\"bar\"}")));

            handlerWithOutputSchema.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_TOOL_NAME)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("170");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"bodySchema\\\":{\\\"foo\\\":\\\"bar\\\"}}\"}],\"structuredContent\":{\"bodySchema\":{\"foo\":\"bar\"}},\"error\":false}}"
                        )
                )
            );
        }
    }

    @Nested
    class ParseError {

        @Test
        void shouldPrepareParseErrorWithWrongJson() {
            when(request.body()).thenReturn(Maybe.just(Buffer.buffer("Not a valid json")));
            cut.handleRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
            assertThat((Boolean) ctx.getInternalAttribute(InternalContextAttributes.ATTR_INTERNAL_INVOKER_SKIP)).isTrue();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isEqualTo("123-456-789");
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();
            assertThat((Boolean) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_ERROR_PARSE_ERROR)).isTrue();

            verify(request, never()).method(any());
            verify(request, never()).pathInfo(any());
            verify(request, never()).body(any());
        }

        @Test
        void shouldReturnParseErrorWithWrongJson() {
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_ERROR_PARSE_ERROR, Boolean.TRUE);

            cut.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("116");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":-1,\"error\":{\"code\":-32700,\"message\":\"Parse error\",\"data\":{\"reason\":\"Json body is not valid\"}}}"
                        )
                )
            );
        }
    }

    @Nested
    class InvalidRequest {

        @Test
        void shouldPrepareInvalidRequest() {
            when(request.body()).thenReturn(
                Maybe.just(
                    Buffer.buffer(
                        """
                        {
                            "error": "not a valid RPC Request"
                        }"""
                    )
                )
            );

            cut.handleRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();
            assertThat((Boolean) ctx.getInternalAttribute(InternalContextAttributes.ATTR_INTERNAL_INVOKER_SKIP)).isTrue();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isEqualTo("123-456-789");
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();
            assertThat((Boolean) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_ERROR_INVALID_REQUEST)).isTrue();

            verify(request, never()).method(any());
            verify(request, never()).pathInfo(any());
            verify(request, never()).body(any());
        }

        @Test
        void shouldReturnInvalidRequest() {
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_ERROR_INVALID_REQUEST, Boolean.TRUE);

            cut.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("125");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":-1,\"error\":{\"code\":-32600,\"message\":\"Invalid request\",\"data\":{\"reason\":\"Json is not a valid request\"}}}"
                        )
                )
            );
        }
    }

    @Nested
    class MethodNotFound {

        @Test
        void shouldReturnMethodNotFound() {
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD, "foobar");
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID, 1);

            cut.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("122");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\",\"data\":{\"reason\":\"Method not found: foobar\"}}}"
                        )
                )
            );
        }
    }

    @Nested
    class InternalError {

        @Test
        void shouldReturnInternalErrorIfRequestPreparedIt() {
            ctx.setInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_ERROR_INTERNAL_ERROR, Boolean.TRUE);

            cut.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("142");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":-1,\"error\":{\"code\":-32603,\"message\":\"Internal server error\",\"data\":{\"reason\":\"Error occurred during request handling\"}}}"
                        )
                )
            );
        }

        @Test
        void shouldReturnInternalErrorIfException() {
            ctx.setInternalAttribute(
                MCPHandler.ATTR_INTERNAL_MCP_ERROR_INTERNAL_ERROR,
                "String instead of a boolean to generate an exception"
            );

            cut.handleResponse(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_SESSION_ID)).isNull();
            assertThat((String) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_METHOD)).isNull();
            assertThat((Integer) ctx.getInternalAttribute(MCPHandler.ATTR_INTERNAL_MCP_REQUEST_ID)).isNull();

            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(responseHeaders.get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("142");
            verify(response).status(200);
            verify(response).body(
                argThat(buffer ->
                    buffer
                        .toString()
                        .equals(
                            "{\"jsonrpc\":\"2.0\",\"id\":-1,\"error\":{\"code\":-32603,\"message\":\"Internal server error\",\"data\":{\"reason\":\"Error occurred during request handling\"}}}"
                        )
                )
            );
        }
    }

    @Nested
    class InvalidParams {}
}
