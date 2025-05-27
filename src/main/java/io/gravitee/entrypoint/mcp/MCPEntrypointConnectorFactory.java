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

import static io.gravitee.entrypoint.mcp.MCPEntrypointConnector.SUPPORTED_MODES;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.entrypoint.mcp.configuration.MCPEntrypointConnectorConfiguration;
import io.gravitee.entrypoint.mcp.service.MCPHandler;
import io.gravitee.gateway.reactive.api.ConnectorMode;
import io.gravitee.gateway.reactive.api.ListenerType;
import io.gravitee.gateway.reactive.api.connector.entrypoint.sync.HttpEntrypointSyncConnectorFactory;
import io.gravitee.gateway.reactive.api.context.DeploymentContext;
import io.gravitee.gateway.reactive.api.exception.PluginConfigurationException;
import io.gravitee.gateway.reactive.api.helper.PluginConfigurationHelper;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author GraviteeSource Team
 */
@Slf4j
@AllArgsConstructor
public class MCPEntrypointConnectorFactory implements HttpEntrypointSyncConnectorFactory {

    private PluginConfigurationHelper pluginConfigurationHelper;

    @Override
    public Set<ConnectorMode> supportedModes() {
        return SUPPORTED_MODES;
    }

    @Override
    public ListenerType supportedListenerType() {
        return MCPEntrypointConnector.SUPPORTED_LISTENER_TYPE;
    }

    @Override
    public MCPEntrypointConnector createConnector(DeploymentContext deploymentContext, String configuration) {
        try {
            MCPEntrypointConnectorConfiguration entrypointConfiguration = pluginConfigurationHelper.readConfiguration(
                MCPEntrypointConnectorConfiguration.class,
                configuration
            );
            return new MCPEntrypointConnector(entrypointConfiguration, new MCPHandler(entrypointConfiguration));
        } catch (PluginConfigurationException | JsonProcessingException e) {
            log.error("Can't create connector cause no valid configuration", e);
            return null;
        }
    }
}
