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
package io.gravitee.entrypoint.mcp;

import static io.gravitee.gateway.api.ExecutionContext.ATTR_CONTEXT_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.util.LinkedMultiValueMap;
import io.gravitee.entrypoint.mcp.configuration.MCPEntrypointConnectorConfiguration;
import io.gravitee.entrypoint.mcp.service.MCPHandler;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ApiType;
import io.gravitee.gateway.reactive.api.ConnectorMode;
import io.gravitee.gateway.reactive.api.ListenerType;
import io.gravitee.gateway.reactive.api.context.ExecutionContext;
import io.gravitee.gateway.reactive.api.context.Request;
import io.gravitee.gateway.reactive.api.context.Response;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author GraviteeSource Team
 */
@ExtendWith(MockitoExtension.class)
class MCPEntrypointConnectorTest {

    @Mock
    private ExecutionContext ctx;

    @Mock
    private Request request;

    @Mock
    private Response response;

    @Mock
    private MCPHandler mcpHandler;

    private MCPEntrypointConnector cut;
    private MCPEntrypointConnectorConfiguration cutConfiguration;

    @BeforeEach
    void beforeEach() throws JsonProcessingException {
        lenient().when(request.headers()).thenReturn(HttpHeaders.create());
        lenient().when(request.parameters()).thenReturn(new LinkedMultiValueMap<>());
        lenient().when(request.body()).thenReturn(Maybe.empty());
        lenient().when(ctx.request()).thenReturn(request);
        lenient().when(ctx.response()).thenReturn(response);
        lenient().when(response.end(ctx)).thenReturn(Completable.complete());
        cutConfiguration = new MCPEntrypointConnectorConfiguration();
        cut = new MCPEntrypointConnector(cutConfiguration, mcpHandler);
    }

    @Test
    void shouldIdReturnMcp() {
        assertThat(cut.id()).isEqualTo("mcp");
    }

    @Test
    void shouldSupportSyncApi() {
        assertThat(cut.supportedApi()).isEqualTo(ApiType.PROXY);
    }

    @Test
    void shouldSupportHttpListener() {
        assertThat(cut.supportedListenerType()).isEqualTo(ListenerType.HTTP);
    }

    @Test
    void shouldSupportRequestResponseMode() {
        assertThat(cut.supportedModes()).containsOnly(ConnectorMode.REQUEST_RESPONSE);
    }

    @Test
    void shouldMatchesCriteriaReturnValidCount() {
        assertThat(cut.matchCriteriaCount()).isEqualTo(3);
    }

    @Test
    void shouldMatchesWithValidContext() {
        HttpHeaders httpHeaders = HttpHeaders.create();
        httpHeaders.set(HttpHeaderNames.ACCEPT, "text/event-stream; application/json");
        when(request.headers()).thenReturn(httpHeaders);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.path()).thenReturn("/contextPath/myMCP");
        when(ctx.getAttribute(ATTR_CONTEXT_PATH)).thenReturn("/contextPath");

        cutConfiguration.setMcpPath("/myMCP");
        boolean matches = cut.matches(ctx);

        assertThat(matches).isTrue();
    }

    @Test
    void shouldNotMatchesWithBadAccept() {
        when(ctx.request()).thenReturn(request);
        HttpHeaders httpHeaders = HttpHeaders.create();
        httpHeaders.set(HttpHeaderNames.ACCEPT, "application/json");
        when(request.headers()).thenReturn(httpHeaders);

        boolean matches = cut.matches(ctx);

        assertThat(matches).isFalse();
    }

    @Test
    void shouldNotMatchesWithBadMethod() {
        when(ctx.request()).thenReturn(request);
        HttpHeaders httpHeaders = HttpHeaders.create();
        httpHeaders.set(HttpHeaderNames.ACCEPT, "text/event-stream; application/json");
        when(request.headers()).thenReturn(httpHeaders);
        when(request.method()).thenReturn(HttpMethod.PUT);

        boolean matches = cut.matches(ctx);

        assertThat(matches).isFalse();
    }

    @Test
    void shouldNotMatchesWithBadPath() {
        when(ctx.request()).thenReturn(request);
        HttpHeaders httpHeaders = HttpHeaders.create();
        httpHeaders.set(HttpHeaderNames.ACCEPT, "text/event-stream; application/json");
        when(request.headers()).thenReturn(httpHeaders);
        when(request.method()).thenReturn(HttpMethod.POST);

        when(request.path()).thenReturn("/contextPath/foo/bar");
        when(ctx.getAttribute(ATTR_CONTEXT_PATH)).thenReturn("/contextPath");

        boolean matches = cut.matches(ctx);

        assertThat(matches).isFalse();
    }
}
