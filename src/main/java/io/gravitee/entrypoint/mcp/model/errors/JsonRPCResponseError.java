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
package io.gravitee.entrypoint.mcp.model.errors;

import io.gravitee.entrypoint.mcp.model.JsonRPC;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class JsonRPCResponseError extends JsonRPC {

    JsonRPCError error;

    public static JsonRPCResponseError newError(Integer id, McpErrorCodes error, String reason) {
        JsonRPCResponseError rpcResponseError = new JsonRPCResponseError();
        rpcResponseError.setId(id);
        JsonRPCError rpcError = new JsonRPCError();
        rpcError.setCode(error.getCode());
        rpcError.setMessage(error.getMessage());
        rpcResponseError.setError(rpcError);

        JsonRPCErrorData data = new JsonRPCErrorData();
        data.setReason(reason);
        rpcError.setData(data);

        return rpcResponseError;
    }
}
