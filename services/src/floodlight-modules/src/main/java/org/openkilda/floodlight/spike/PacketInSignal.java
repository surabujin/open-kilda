package org.openkilda.floodlight.spike;

import net.floodlightcontroller.packet.Ethernet;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.types.DatapathId;

public class PacketInSignal extends OFMessageSignal {
    Ethernet ethPacket;

    public PacketInSignal(DatapathId dpId, OFMessage payload, Ethernet ethPacket) {
        super(dpId, payload);
        this.ethPacket = ethPacket;
    }

    public Ethernet getEthPacket() {
        return ethPacket;
    }
}
