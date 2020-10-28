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
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmFactory;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.Event;
import org.openkilda.wfm.topology.network.model.BfdSessionData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BfdSessionController {
    private final BfdSessionFsm.BfdSessionFsmFactory fsmFactory;

    private final Endpoint logical;
    private final int physicalPortNumber;

    private BfdSessionFsm fsm;
    private BfdSessionData sessionData;

    public BfdSessionController(
            BfdSessionFsm.BfdSessionFsmFactory fsmFactory, Endpoint logical, int physicalPortNumber) {
        this.fsmFactory = fsmFactory;

        this.logical = logical;
        this.physicalPortNumber = physicalPortNumber;
    }

    public void enableUpdate(BfdSessionData sessionData) {
        this.sessionData = sessionData;
        rotate();
    }

    public void disable() {
        handle(Event.DISABLE);
    }

    public void speakerResponse(String key) {
        if (isFsmExists()) {
            fsm.speakerResponse(key);
            rotateIfCompleted();
        }
    }

    public void speakerResponse(String key, BfdSessionResponse response) {
        if (isFsmExists()) {
            fsm.speakerResponse(key, response);
            rotateIfCompleted();
        }
    }

    private void handle(Event event) {
        handle(event, BfdSessionFsmContext.builder().build());
    }

    private void handle(Event event, BfdSessionFsmContext context) {
        if (fsm == null) {
            log.error(
                    "There is no active BFD session FSM for {}, ignore event {} with context {}",
                    logical, event, context);
            emitCompleteNotification();
            return;
        }

        BfdSessionFsmFactory.EXECUTOR.fire(fsm, event, context);
        rotateIfCompleted();
    }

    private void rotateIfCompleted() {
        if (!fsm.isTerminated()) {
            return;
        }

        if (fsm.isError() && sessionData == null) {
            sessionData = fsm.getSessionData();
        }

        fsm = null;
        rotate();
    }

    private void rotate() {
        if (sessionData == null) {
            emitCompleteNotification();
            return;
        }

        if (fsm == null) {
            fsm = fsmFactory.produce(sessionData, logical, physicalPortNumber);
            fsm.disableIfConfigured();
        }

        if (fsm.enableIfReady()) {
            sessionData = null;
        }
    }

    private boolean isFsmExists() {
        if (fsm == null) {
            log.error("There si no active BFD session FSM for {}", logical);
            emitCompleteNotification();
            return false;
        }
        return true;
    }

    private void emitCompleteNotification() {
        fsmFactory.getCarrier().sessionCompleteNotification(getPhysical());
    }

    private Endpoint getPhysical() {
        return Endpoint.of(logical.getDatapath(), physicalPortNumber);
    }
}
