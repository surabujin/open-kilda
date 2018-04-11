package org.openkilda.floodlight.spike;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.types.DatapathId;

public class OFMessageSignal extends Signal {
    private final DatapathId dpId;
    private final OFMessage payload;

    public OFMessageSignal(DatapathId dpId, OFMessage payload) {
        this.dpId = dpId;
        this.payload = payload;
    }

    public OFType getType() {
        return payload.getType();
    }

    public DatapathId getDpId() {
        return dpId;
    }

    public OFMessage getPayload() {
        return payload;
    }
}
