package org.openkilda.floodlight.issue;

import com.google.common.collect.ImmutableList;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.IPv4;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class PingGenerator implements Runnable {
    private static Logger logger = LoggerFactory.getLogger(PingGenerator.class);

    private final DatapathId swId;
    private final IOFSwitchService switchService;

    public PingGenerator(DatapathId swId, IOFSwitchService switchService) {
        this.swId = swId;
        this.switchService = switchService;
    }

    @Override
    public void run() {
        logger.info("make ping");

        sendBatch("xe48", 1L, null);
    }

    private void sendBatch(String portName, Long countLimit, Long timeLimitMs) {
        logger.info("Send batch of ip packets: port={}, count={}, time={}(ms)", portName, countLimit, timeLimitMs);

        long fails = 0;
        long success = 0;

        long stime = System.currentTimeMillis();
        long ctime = stime;
        Long etime = null;
        if (timeLimitMs != null) {
            etime = stime + timeLimitMs;
        }

        Long count = 0L;
        while (true) {
            try {
                if (sendPackage(portName)) {
                    success += 1;
                } else {
                    fails += 1;
                }
            } catch (Exception e) {
                logger.error("Ping failed!", e);
            }

            count += 1;
            ctime = System.currentTimeMillis();

            if (countLimit != null && countLimit <= count) {
                break;
            }
            if (etime != null && etime <= ctime) {
                break;
            }
        }

        logger.info("Done success - {} fails - {}. Work time: {}ms", success, fails, ctime - stime);
    }

    private boolean sendPackage(String portName) {
        IOFSwitch sw = switchService.getSwitch(swId);

        OFPortDesc port = sw.getPort(portName);
        OFPacketOut packet = sw.getOFFactory().buildPacketOut()
                .setInPort(OFPort.CONTROLLER)
                .setActions(
                        ImmutableList.of(
                                sw.getOFFactory()
                                        .actions()
                                        .buildOutput()
                                        .setPort(port.getPortNo())
                                        .build()))
                .setData(makePayload())
                .build();

        boolean isSuccess = sw.write(packet);
        logger.info("Send packet: {}", isSuccess);

        return isSuccess;
    }

    private byte[] makePayload() {
        Ethernet l2 = new Ethernet()
                .setSourceMACAddress(MacAddress.of("08:ED:02:00:00:AA"))
                .setDestinationMACAddress("08:ED:02:00:00:33")
                .setEtherType(EthType.IPv4);

        IPv4 l3 = new IPv4()
                .setProtocol(IpProtocol.ICMP)
                .setSourceAddress(IPv4Address.of("172.16.64.64"))
                .setDestinationAddress("172.16.128.128")
                .setTtl((byte) 8);
        l3.setParent(l2);

        ICMP icmp = new ICMP()
                .setIcmpType(ICMP.ECHO_REQUEST);
        icmp.setParent(l3);

        int count = 32;
        byte[] data = new byte[count];
        ByteBuffer bb = ByteBuffer.wrap(data);
        int base = (int)'A';
        for (int idx = 0; idx < count; idx++) {
            bb.put((byte) (base + idx));
        }
        Data payload = new Data(data);
        payload.setParent(icmp);

        return l2.serialize();
    }
}
