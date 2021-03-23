/*
 * Copyright 2021 Telstra Open Source
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

package org.openkilda.messaging.model;

import org.openkilda.model.SwitchConnectMode;

import lombok.Builder;
import lombok.Value;

import java.io.Serializable;
import java.net.SocketAddress;
import java.time.Duration;

@Value
@Builder
public class SwitchAvailabilityEntry implements Serializable {
    String regionName;

    SwitchConnectMode connectMode;

    boolean master;

    Duration connectDuration;

    SocketAddress switchAddress;
    SocketAddress speakerAddress;
}
