package org.openkilda.atdd.staging.steps.helpers;

import org.openkilda.atdd.staging.model.topology.TopologyDefinition;
import org.openkilda.atdd.staging.model.topology.TopologyDefinition.Trafgen;
import org.openkilda.atdd.staging.service.traffexam.FlowNotApplicableException;
import org.openkilda.atdd.staging.service.traffexam.model.FlowBidirectionalExam;
import org.openkilda.messaging.model.NetworkEndpoint;
import org.openkilda.messaging.payload.flow.FlowEndpointPayload;
import org.openkilda.messaging.payload.flow.FlowPayload;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FlowTrafficExamBuilder {
    @Autowired
    private TopologyDefinition topology;

    private Map<NetworkEndpoint, Trafgen> endpointToTrafgen = new HashMap<>();

    public FlowTrafficExamBuilder() {
        for (Trafgen trafgen : topology.getActiveTrafgens()) {
            NetworkEndpoint endpoint = new NetworkEndpoint(
                    trafgen.getSwitchConnected().getDpId(), trafgen.getSwitchPort());
            endpointToTrafgen.put(endpoint, trafgen);
        }
    }

    public FlowBidirectionalExam makeBidirectionalExam(FlowPayload flow) throws FlowNotApplicableException {
        Optional<Trafgen> source = Optional.ofNullable(
                endpointToTrafgen.get(makeComparableEndpoint(flow.getSource())));
        Optional<Trafgen> dest = Optional.ofNullable(
                endpointToTrafgen.get(makeComparableEndpoint(flow.getDestination())));

        checkIsFlowApplicable(flow, source.isPresent(), dest.isPresent());

        //noinspection ConstantConditions
        return new FlowBidirectionalExam(flow, source.get(), dest.get());
    }

    private void checkIsFlowApplicable(FlowPayload flow, boolean sourceApplicable, boolean destApplicable)
            throws FlowNotApplicableException {
        String message;

        if (!sourceApplicable && !destApplicable) {
            message = "source endpoint and destination endpoint are";
        } else if (! sourceApplicable) {
            message = "source endpoint is";
        } else if (! destApplicable) {
            message = "dest endpoint is";
        } else {
            message = null;
        }

        if (message != null) {
            throw new FlowNotApplicableException(String.format(
                    "Flow's %s %s not applicable for traffic examination.", flow.getId(), message));
        }
    }

    private NetworkEndpoint makeComparableEndpoint(FlowEndpointPayload flowEndpoint) {
        return new NetworkEndpoint(flowEndpoint);
    }
}
