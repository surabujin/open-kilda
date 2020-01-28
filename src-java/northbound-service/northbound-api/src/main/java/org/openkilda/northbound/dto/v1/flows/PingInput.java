/* Copyright 2018 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.northbound.dto.v1.flows;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

import java.util.Optional;

@Value
public class PingInput {
    public static final int DEFAULT_TIMEOUT = 2000;

    @JsonProperty("timeout")
    private int timeoutMillis;

    @JsonCreator
    public PingInput(
            @JsonProperty("timeout") Integer timeoutMillis) {
        this.timeoutMillis = Optional.ofNullable(timeoutMillis).orElse(DEFAULT_TIMEOUT);
    }
}
