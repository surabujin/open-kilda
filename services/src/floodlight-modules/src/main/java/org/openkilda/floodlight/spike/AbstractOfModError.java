package org.openkilda.floodlight.spike;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.types.DatapathId;

public abstract class AbstractOfModError extends Exception {
    private final DatapathId dpId;
    private final OFMessage payload;

    public AbstractOfModError(DatapathId dpId, OFMessage payload) {
        this(dpId, payload, makeErrorMessage(dpId, payload));
    }

    public AbstractOfModError(DatapathId dpId, OFMessage payload, String message) {
        super(message);
        this.dpId = dpId;
        this.payload = payload;
    }

    private static String makeErrorMessage(DatapathId dpId, OFMessage payload) {
        return String.format("Unable to unstall OFRule (dpId: %s, xId: %s)", dpId, payload.getXid());
    }

    public DatapathId getDpId() {
        return dpId;
    }

    public OFMessage getPayload() {
        return payload;
    }
}
