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

import static io.gravitee.common.http.MediaType.APPLICATION_JSON;
import static io.gravitee.common.http.MediaType.TEXT_EVENT_STREAM;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.entrypoint.mcp.configuration.MCPEntrypointConnectorConfiguration;
import io.gravitee.entrypoint.mcp.service.MCPHandler;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.reactive.api.ConnectorMode;
import io.gravitee.gateway.reactive.api.ListenerType;
import io.gravitee.gateway.reactive.api.connector.entrypoint.sync.HttpEntrypointSyncConnector;
import io.gravitee.gateway.reactive.api.context.http.HttpExecutionContext;
import io.reactivex.rxjava3.core.Completable;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * @author GraviteeSource Team
 */
@Slf4j
public class MCPEntrypointConnector extends HttpEntrypointSyncConnector {

    static final Set<ConnectorMode> SUPPORTED_MODES = Set.of(ConnectorMode.REQUEST_RESPONSE);
    static final ListenerType SUPPORTED_LISTENER_TYPE = ListenerType.HTTP;

    private static final String ENTRYPOINT_ID = "mcp";

    private final MCPEntrypointConnectorConfiguration configuration;
    private final MCPHandler mcpHandler;

    public MCPEntrypointConnector(MCPEntrypointConnectorConfiguration configuration, MCPHandler mcpHandler) throws JsonProcessingException {
        if (configuration == null) {
            this.configuration = new MCPEntrypointConnectorConfiguration();
        } else {
            this.configuration = configuration;
        }

        this.mcpHandler = mcpHandler;
    }

    @Override
    public String id() {
        return ENTRYPOINT_ID;
    }

    @Override
    public ListenerType supportedListenerType() {
        return SUPPORTED_LISTENER_TYPE;
    }

    @Override
    public Set<ConnectorMode> supportedModes() {
        return SUPPORTED_MODES;
    }

    @Override
    public int matchCriteriaCount() {
        // Accept = text/event-stream, application/json and Method = POST and  Path = /context-path/mcp
        return 3;
    }

    @Override
    public boolean matches(final HttpExecutionContext ctx) {
        String acceptHeader = ctx.request().headers().get(HttpHeaderNames.ACCEPT);
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        return (
            acceptHeader.contains(TEXT_EVENT_STREAM) &&
            acceptHeader.contains(APPLICATION_JSON) &&
            HttpMethod.POST == ctx.request().method() &&
            ctx.request().path().equals(getActualMcpPath(ctx))
        );
    }

    @Override
    public Completable handleRequest(final HttpExecutionContext ctx) {
        return mcpHandler.handleRequest(ctx);
    }

    @Override
    public Completable handleResponse(HttpExecutionContext ctx) {
        return mcpHandler.handleResponse(ctx);
    }

    private String getActualMcpPath(HttpExecutionContext ctx) {
        String apiContextPath = ctx.getAttribute(ExecutionContext.ATTR_CONTEXT_PATH);
        if (apiContextPath.endsWith("/")) {
            apiContextPath = apiContextPath.substring(0, apiContextPath.length() - 1);
        }
        if (!configuration.getMcpPath().startsWith("/")) {
            return apiContextPath + "/" + configuration.getMcpPath();
        }
        return apiContextPath + configuration.getMcpPath();
    }
}
