package org.openkilda.floodlight.spike;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.types.DatapathId;

public class OFModError extends AbstractOfModError {
    public OFModError(DatapathId dpId, OFMessage payload) {
        super(dpId, payload, makeErrorMessage(dpId, payload));
    }

    private static String makeErrorMessage(DatapathId dpId, OFMessage payload) {
        return String.format("Switch have rejected OFMod request (dpId: %s, xId: %s)", dpId, payload.getXid());
    }
}
