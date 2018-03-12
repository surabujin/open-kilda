package org.openkilda.floodlight.issue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.threadpool.IThreadPoolService;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.types.DatapathId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AcctonOfFlood implements IFloodlightModule, IFloodlightService, IOFSwitchListener {
    private static final Logger logger = LoggerFactory.getLogger(AcctonOfFlood.class);

    private IOFSwitchService switchService;
    private IThreadPoolService threadPoolService;

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return ImmutableList.of(AcctonOfFlood.class);
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return ImmutableMap.of(AcctonOfFlood.class, this);
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return ImmutableList.of(
                IFloodlightProviderService.class,
                IThreadPoolService.class,
                IOFSwitchService.class);
    }

    @Override
    public void init(FloodlightModuleContext floodlightModuleContext) throws FloodlightModuleException {
        logger.info("STARTED", this.getClass().getCanonicalName());
    }

    @Override
    public void startUp(FloodlightModuleContext FlContext) throws FloodlightModuleException {
        switchService = FlContext.getServiceImpl(IOFSwitchService.class);
        threadPoolService = FlContext.getServiceImpl(IThreadPoolService.class);

        FlContext.getServiceImpl(IOFSwitchService.class)
                .addOFSwitchListener(this);
    }

    @Override
    public void switchAdded(DatapathId switchId) {
        logger.info("Switch added {}", switchId);
    }

    @Override
    public void switchRemoved(DatapathId switchId) {
        logger.info("Switch removed {}", switchId);
    }

    @Override
    public void switchActivated(DatapathId switchId) {
        logger.info("Switch activated {}", switchId);

        IOFSwitch sw = switchService.getSwitch(switchId);
        logger.info("Switch: {}", sw);
        logger.info("Dump ports of {}", switchId);
        for (OFPortDesc port : sw.getPorts()) {
            logger.info("{} {} {}", port.getPortNo(), port.getHwAddr(), port.getName());
        }

        threadPoolService.getScheduledExecutor().schedule(
                new PingGenerator(switchId, switchService), 1, TimeUnit.SECONDS);
    }

    @Override
    public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {
        logger.info("Switch port changed {}: port - {}, kind - {}", switchId, port, type);
    }

    @Override
    public void switchChanged(DatapathId switchId) {
        logger.info("Switch changed {}", switchId);
    }

    @Override
    public void switchDeactivated(DatapathId switchId) {
        logger.info("Switch deactivated {}", switchId);
    }
}
