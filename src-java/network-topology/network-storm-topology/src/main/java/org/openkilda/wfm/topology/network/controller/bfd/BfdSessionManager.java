/* Copyright 2020 Telstra Open Source
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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.model.BfdProperties;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmContext;
import org.openkilda.wfm.topology.network.model.BfdSessionData;

public interface BfdSessionManager {
    BfdSessionManager rotate(BfdSessionBlank sessionBlank);

    boolean enableIfReady();

    void speakerResponse(String key);

    void speakerResponse(String key, BfdSessionResponse response);

    void handle(BfdSessionFsm.Event event);

    void handle(BfdSessionFsm.Event event, BfdSessionFsmContext context);

    boolean isDummy();
}
