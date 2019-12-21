/* Copyright 2019 Telstra Open Source
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

package org.openkilda.floodlight.command.flow.shared;

import org.openkilda.floodlight.command.SpeakerCommandProcessor;
import org.openkilda.floodlight.command.flow.FlowSegmentReport;
import org.openkilda.floodlight.model.FlowSegmentMetadata;
import org.openkilda.messaging.MessageContext;
import org.openkilda.model.FlowEndpoint;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SharedIngressFlowSegmentOuterVlanMatchVerifyCommand
        extends SharedIngressFlowSegmentOuterVlanMatchInstallCommand {
    public SharedIngressFlowSegmentOuterVlanMatchVerifyCommand(
            @JsonProperty("message_context") MessageContext messageContext,
            @JsonProperty("command_id") UUID commandId,
            @JsonProperty("metadata") FlowSegmentMetadata metadata,
            @JsonProperty("endpoint") FlowEndpoint endpoint) {
        super(messageContext, commandId, metadata, endpoint);
    }

    @Override
    protected CompletableFuture<FlowSegmentReport> makeExecutePlan(SpeakerCommandProcessor commandProcessor) {
        return makeVerifyPlan(Collections.singletonList(makeFlowModMessage()));
    }

    @Override
    protected SegmentAction getSegmentAction() {
        return SegmentAction.VERIFY;
    }
}
