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
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.Event;
import org.openkilda.wfm.topology.network.model.BfdSessionData;
import org.openkilda.wfm.topology.network.service.IBfdSessionCarrier;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BfdSessionDummy implements BfdSessionManager {
    private final BfdSessionFsm.BfdSessionFsmFactory factory;

    private final Endpoint logical;
    private final int physicalPortNumber;

    public BfdSessionDummy(BfdSessionFsm.BfdSessionFsmFactory factory, Endpoint logical, int physicalPortNumber) {
        this.factory = factory;
        this.logical = logical;
        this.physicalPortNumber = physicalPortNumber;
    }

    @Override
    public BfdSessionManager rotate(BfdSessionBlank sessionBlank) {
        return factory.produce(sessionBlank);
    }

    @Override
    public boolean enableIfReady() {
        throw new IllegalStateException("Must never be called");
    }

    @Override
    public void speakerResponse(String key) {
        reportMissingManger(String.format("speakerResponse(\"%s\")", key));
    }

    @Override
    public void speakerResponse(String key, BfdSessionResponse response) {
        reportMissingManger(String.format("speakerResponse(\"%s\", %s)", key, response));
    }

    @Override
    public void handle(Event event) {
        reportMissingManger(String.format("fire(%s)", event));
    }

    @Override
    public void handle(Event event, BfdSessionFsmContext context) {
        reportMissingManger(String.format("fire(%s, %s)", event, context));
    }

    @Override
    public boolean isDummy() {
        return true;
    }

    private void reportMissingManger(String callDetails) {
        log.error("There si no active BFD session FSM for {} - ignore call {}", logical, callDetails);
        emitCompleteNotification();
    }

    private void emitCompleteNotification() {
        factory.getCarrier().sessionCompleteNotification(Endpoint.of(logical.getDatapath(), physicalPortNumber));
    }
}
