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

package org.openkilda.northbound.controller.v2;

import org.openkilda.northbound.controller.BaseController;
import org.openkilda.northbound.dto.v2.flows.FlowRequestV2;
import org.openkilda.northbound.dto.v2.flows.FlowRerouteResponseV2;
import org.openkilda.northbound.dto.v2.flows.FlowResponseV2;
import org.openkilda.northbound.dto.v2.flows.SwapFlowEndpointPayload;
import org.openkilda.northbound.service.FlowService;

import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/v2/flows")
public class FlowControllerV2 extends BaseController {

    @Autowired
    private FlowService flowService;

    @ApiOperation(value = "Creates new flow", response = FlowResponseV2.class)
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowResponseV2> createFlow(@RequestBody FlowRequestV2 flow) {
        return flowService.createFlow(flow);
    }

    /**
     * Initiates flow rerouting if any shorter path is available.
     *
     * @param flowId id of flow to be rerouted.
     * @return the flow with updated path.
     */
    @ApiOperation(value = "Reroute flow", response = FlowRerouteResponseV2.class)
    @PostMapping(path = "/{flow_id}/reroute")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<FlowRerouteResponseV2> rerouteFlow(@PathVariable("flow_id") String flowId) {
        return flowService.rerouteFlowV2(flowId);
    }


    /**
     * Bulk update for flow.
     */
    @ApiOperation(value = "Swap flow endpoints", response = SwapFlowEndpointPayload.class)
    @PostMapping("/swap-endpoint")
    @ResponseStatus(HttpStatus.OK)
    public CompletableFuture<SwapFlowEndpointPayload> swapFlowEndpoint(@RequestBody SwapFlowEndpointPayload payload) {
        return flowService.swapFlowEndpoint(payload);
    }
}
