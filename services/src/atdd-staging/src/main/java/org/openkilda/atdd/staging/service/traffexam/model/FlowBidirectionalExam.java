package org.openkilda.atdd.staging.service.traffexam.model;

import org.openkilda.atdd.staging.model.topology.TopologyDefinition.Trafgen;
import org.openkilda.atdd.staging.service.traffexam.TraffExamService;
import org.openkilda.messaging.payload.flow.FlowPayload;

import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

public class FlowBidirectionalExam {
    private final FlowPayload flow;
    private final Exam forward;
    private final Exam reverse;

    @Autowired
    private TraffExamService traffExam;

    public FlowBidirectionalExam(FlowPayload flow, Trafgen source, Trafgen dest) {
        this.flow = flow;

        Host sourceHost = traffExam.hostByName(source.getName());
        Host destHost = traffExam.hostByName(dest.getName());

        forward = new Exam(sourceHost, destHost)
                .withSourceVlan(new Vlan(flow.getSource().getVlanId()))
                .withDestVlan(new Vlan(flow.getDestination().getVlanId()))
                .withBandwidthLimit(new Bandwidth(flow.getMaximumBandwidth()));
        reverse = new Exam(destHost, sourceHost)
                .withSourceVlan(new Vlan(flow.getDestination().getVlanId()))
                .withDestVlan(new Vlan(flow.getSource().getVlanId()))
                .withBandwidthLimit(new Bandwidth(flow.getMaximumBandwidth()));
    }

    public FlowPayload getFlow() {
        return flow;
    }

    public List<Exam> getExamPair() {
        return ImmutableList.of(forward, reverse);
    }

    public Exam getForward() {
        return forward;
    }

    public Exam getReverse() {
        return reverse;
    }
}
