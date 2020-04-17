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

package org.openkilda.wfm.topology.network.controller.isl;

import org.openkilda.messaging.command.reroute.RerouteAffectedFlows;
import org.openkilda.messaging.command.reroute.RerouteInactiveFlows;
import org.openkilda.messaging.info.event.IslStatusUpdateNotification;
import org.openkilda.messaging.info.event.PathNode;
import org.openkilda.model.FeatureToggles;
import org.openkilda.model.Isl;
import org.openkilda.model.Isl.IslBuilder;
import org.openkilda.model.IslDownReason;
import org.openkilda.model.IslStatus;
import org.openkilda.model.LinkProps;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.model.SwitchProperties;
import org.openkilda.model.SwitchStatus;
import org.openkilda.persistence.PersistenceException;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.TransactionManager;
import org.openkilda.persistence.repositories.FeatureTogglesRepository;
import org.openkilda.persistence.repositories.FlowPathRepository;
import org.openkilda.persistence.repositories.IslRepository;
import org.openkilda.persistence.repositories.LinkPropsRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchPropertiesRepository;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.model.IslReference;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.NetworkTopologyDashboardLogger;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmContext;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmEvent;
import org.openkilda.wfm.topology.network.controller.isl.IslFsm.IslFsmState;
import org.openkilda.wfm.topology.network.model.BiIslDataHolder;
import org.openkilda.wfm.topology.network.model.IslDataHolder;
import org.openkilda.wfm.topology.network.model.IslEndpointRoundTripStatus;
import org.openkilda.wfm.topology.network.model.IslEndpointStatus;
import org.openkilda.wfm.topology.network.model.NetworkOptions;
import org.openkilda.wfm.topology.network.model.RoundTripStatus;
import org.openkilda.wfm.topology.network.service.IIslCarrier;
import org.openkilda.wfm.topology.network.storm.bolt.isl.BfdManager;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.RetryPolicy;
import org.squirrelframework.foundation.fsm.Condition;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class IslFsm extends AbstractBaseFsm<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> {
    private final IslReference reference;
    private IslStatus effectiveStatus = IslStatus.INACTIVE;
    private IslDownReason downReason;

    private final List<DiscoveryMonitor<?>> monitorsByPriority;

    private final BiIslDataHolder<Boolean> endpointMultiTableManagementCompleteStatus;

    private final NetworkTopologyDashboardLogger dashboardLogger;

    // FIXME - start
    private final Clock clock;

    private final BfdManager bfdManager;

    private final IslRepository islRepository;
    private final LinkPropsRepository linkPropsRepository;
    private final FlowPathRepository flowPathRepository;
    private final SwitchRepository switchRepository;
    private final TransactionManager transactionManager;
    private final FeatureTogglesRepository featureTogglesRepository;
    private final SwitchPropertiesRepository switchPropertiesRepository;

    private final RetryPolicy transactionRetryPolicy;

    private final BiIslDataHolder<IslEndpointStatus> endpointStatus;
    private final BiIslDataHolder<IslEndpointRoundTripStatus> endpointRoundTripStatus;

    private boolean ignoreRerouteOnUp = false;  // TODO: ??? do not work now
    private final NetworkOptions options;
    private long islRulesAttempts;
    // FIXME - end

    public static IslFsmFactory factory(Clock clock, PersistenceManager persistenceManager,
                                        NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder) {
        return new IslFsmFactory(clock, persistenceManager, dashboardLoggerBuilder);
    }

    public IslFsm(Clock clock, PersistenceManager persistenceManager, NetworkTopologyDashboardLogger dashboardLogger,
                  BfdManager bfdManager, NetworkOptions options, IslReference reference) {
        this.reference = reference;

        monitorsByPriority = ImmutableList.of(
                new DiscoveryPortStatusMonitor(reference),
                new DiscoveryBfdMonitor(reference),
                new DiscoveryRoundTripMonitor(reference, clock, options),
                new DiscoveryPollMonitor(reference));

        endpointMultiTableManagementCompleteStatus = new BiIslDataHolder<>(reference);

        this.dashboardLogger = dashboardLogger;

        // FIXME - start
        this.clock = clock;

        this.bfdManager = bfdManager;

        transactionManager = persistenceManager.getTransactionManager();
        transactionRetryPolicy = transactionManager.makeRetryPolicyBlank()
                .withMaxDuration(options.getDbRepeatMaxDurationSeconds(), TimeUnit.SECONDS);

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        islRepository = repositoryFactory.createIslRepository();
        linkPropsRepository = repositoryFactory.createLinkPropsRepository();
        flowPathRepository = repositoryFactory.createFlowPathRepository();
        switchRepository = repositoryFactory.createSwitchRepository();
        featureTogglesRepository = repositoryFactory.createFeatureTogglesRepository();
        switchPropertiesRepository = repositoryFactory.createSwitchPropertiesRepository();

        endpointStatus = new BiIslDataHolder<>(reference);
        endpointStatus.putBoth(new IslEndpointStatus(IslEndpointStatus.Status.DOWN));

        endpointRoundTripStatus = new BiIslDataHolder<>(reference);
        endpointRoundTripStatus.put(reference.getSource(), new IslEndpointRoundTripStatus());
        endpointRoundTripStatus.put(reference.getDest(), new IslEndpointRoundTripStatus());

        this.options = options;
        // FIXME - start
    }

    // -- FSM actions --

    public void loadPersistedState(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        transactionManager.doInTransaction(() -> {
            loadPersistentData(reference.getSource(), reference.getDest());
            loadPersistentData(reference.getDest(), reference.getSource());
        });

        evaluateStatus();
        emitBecomeStateEvent(context);
    }

    public void updateMonitorsAction(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        boolean isSyncRequired = false;
        for (DiscoveryMonitor<?> entry : monitorsByPriority) {
            isSyncRequired |= entry.update(event, context);
        }

        if (evaluateStatus()) {
            emitBecomeStateEvent(context);
        } else if (isSyncRequired) {
            fire(IslFsmEvent._FLUSH);
        }
    }

    public void flushAction(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        flushTransaction();
    }

    public void removeAttempt(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        if (effectiveStatus != IslStatus.ACTIVE) {
            fire(IslFsmEvent._REMOVE_CONFIRMED, context);
        }
    }


    public void setUpResourcesEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        log.info("ISL {} initiate speaker resources setup process", reference);

        islRulesAttempts = options.getRulesSynchronizationAttempts();
        endpointMultiTableManagementCompleteStatus.putBoth(false);

        sendInstallMultiTable(context);

        if (isMultiTableManagementCompleted()) {
            fire(IslFsmEvent._RESOURCES_DONE, context);
        }
    }

    // FIXME(surabujin): protect from stale responses
    public void handleInstalledRule(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        Endpoint endpoint = context.getInstalledRulesEndpoint();
        log.info("Receive response on speaker resource setup request for {} (endpoint {})", reference, endpoint);

        if (endpoint != null) {
            endpointMultiTableManagementCompleteStatus.put(endpoint, true);
            if (isMultiTableManagementCompleted()) {
                fire(IslFsmEvent._RESOURCES_DONE, context);
            }
        }
    }

    public void setUpResourcesTimeout(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        if (--islRulesAttempts >= 0) {
            log.info("Retrying to install rules for multi table mode on isl {}", reference);
            sendInstallMultiTable(context);
        } else {
            log.warn("Failed to install rules for multi table mode on isl {}, required manual rule sync",
                    reference);
            fire(IslFsmEvent._RESOURCES_DONE, context);
        }
    }

    public void activeEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        dashboardLogger.onIslUp(reference);

        flushTransaction();
        sendBfdEnable(context.getOutput());

        triggerDownFlowReroute(context);
    }

    public void inactiveEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        dashboardLogger.onIslDown(reference);
        flushTransaction();

        sendIslStatusUpdateNotification(context, IslStatus.INACTIVE);
        triggerAffectedFlowReroute(context);
    }

    public void movedEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        dashboardLogger.onIslMoved(reference);
        flushTransaction();

        bfdManager.disable(context.getOutput());
        sendIslStatusUpdateNotification(context, IslStatus.MOVED);
        triggerAffectedFlowReroute(context);
    }

    public void cleanUpResourcesEnter(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        log.info("ISL {} initiate speaker resources removal process", reference);

        islRulesAttempts = options.getRulesSynchronizationAttempts();
        endpointMultiTableManagementCompleteStatus.putBoth(false);

        sendRemoveMultiTable(context);

        if (isMultiTableManagementCompleted()) {
            fire(IslFsmEvent._RESOURCES_DONE, context);
        }
    }

    // FIXME(surabujin): protect from stale responses
    public void handleRemovedRule(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        Endpoint endpoint = context.getRemovedRulesEndpoint();
        log.info("Receive response on speaker resource setup request for {} (endpoint {})", reference, endpoint);

        if (endpoint != null) {
            endpointMultiTableManagementCompleteStatus.put(endpoint, true);
            if (isMultiTableManagementCompleted()) {
                fire(IslFsmEvent._RESOURCES_DONE, context);
            }
        }
    }

    public void cleanUpResourcesTimeout(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        if (--islRulesAttempts >= 0) {
            log.info("Retrying to remove rules for multi table mode on isl {}", reference);
            sendRemoveMultiTable(context);
        } else {
            log.warn("Failed to remove rules for multi table mode on isl {}, required manual rule sync",
                    reference);
            fire(IslFsmEvent._RESOURCES_DONE, context);
        }
    }


    // FIXME - {{{
    public void historyRestoreUp(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        sendBfdEnable(context.getOutput());
    }

    public void handleInitialDiscovery(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateLinkData(context.getEndpoint(), context.getIslData());
        updateEndpointStatusByEvent(event, context);
        saveStatusTransaction();
    }

    public void updateEndpointStatus(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateEndpointStatusByEvent(event, context);
    }

    public void updateAndPersistEndpointStatus(IslFsmState from, IslFsmState to, IslFsmEvent event,
                                               IslFsmContext context) {
        updateEndpointStatusByEvent(event, context);
        saveStatusTransaction();
    }

    public void handleUpAttempt(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateLinkData(context.getEndpoint(), context.getIslData());

        IslFsmEvent route;
        if (evaluateStatus() == IslEndpointStatus.Status.UP) {
            route = IslFsmEvent._UP_ATTEMPT_SUCCESS;
        } else {
            route = IslFsmEvent._UP_ATTEMPT_FAIL;
        }
        fire(route, context);
    }

    public void upHandlePhysicalDown(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateEndpointStatusByEvent(event, context);
        saveStatusAndSetIslUnstableTimeTransaction();
    }

    public void upHandlePollEvent(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        updateEndpointStatusByEvent(event, context);
        saveStatusTransaction();

        if (evaluateStatus() != IslEndpointStatus.Status.UP) {
            fire(IslFsmEvent._BECOME_DOWN, context);
        }
    }

    public void upHandleRoundTripStatus(IslFsmState from, IslFsmState to, IslFsmEvent event, IslFsmContext context) {
        RoundTripStatus status = context.getRoundTripStatus();
        IslEndpointRoundTripStatus endpointData = endpointRoundTripStatus.get(status.getEndpoint());
        endpointData.setExpireAt(evaluateRoundTripExpireAtTime(status));

        IslStatus actualState = evaluateRoundTripStatus(endpointData, clock.instant());
        if (! Objects.equals(actualState, endpointData.getStoredStatus())) {
            saveStatusTransaction();
        }

        if (evaluateStatus() != IslEndpointStatus.Status.UP) {
            fire(IslFsmEvent._BECOME_DOWN, context);
        }
    }

    // -- private/service methods --

    private void sendInstallMultiTable(IslFsmContext context) {
        final Endpoint source = reference.getSource();
        final Endpoint dest = reference.getDest();
        sendInstallMultiTable(context.getOutput(), source, dest);
        sendInstallMultiTable(context.getOutput(), dest, source);
    }

    private void sendInstallMultiTable(IIslCarrier carrier, Endpoint ingress, Endpoint egress) {
        Boolean isCompleted = endpointMultiTableManagementCompleteStatus.get(ingress);
        if (isCompleted != null && isCompleted) {
            return;
        }

        if (isSwitchInMultiTableMode(ingress.getDatapath())) {
            carrier.islDefaultRulesInstall(ingress, egress);
        } else {
            endpointMultiTableManagementCompleteStatus.put(ingress, true);
        }
    }

    private void sendRemoveMultiTable(IslFsmContext context) {
        final Endpoint source = reference.getSource();
        final Endpoint dest = reference.getDest();
        sendRemoveMultiTable(context.getOutput(), source, dest);
        sendRemoveMultiTable(context.getOutput(), dest, source);
    }

    private void sendRemoveMultiTable(IIslCarrier carrier, Endpoint ingress, Endpoint egress) {
        Boolean isCompleted = endpointMultiTableManagementCompleteStatus.get(ingress);
        if (isCompleted != null && isCompleted) {
            return;
        }

        // TODO(surabujin): why for we need this check?
        boolean isIslRemoved = islRepository.findByEndpoint(ingress.getDatapath(), ingress.getPortNumber())
                .isEmpty();
        if (isSwitchInMultiTableMode(ingress.getDatapath()) && isIslRemoved) {
                carrier.islDefaultRulesDelete(ingress, egress);
        } else {
            endpointMultiTableManagementCompleteStatus.put(ingress, true);
        }
    }

    private void sendBfdEnable(IIslCarrier carrier) {
        if (shouldSetupBfd()) {
            bfdManager.enable(carrier);
        }
    }

    private void sendIslStatusUpdateNotification(IslFsmContext context, IslStatus status) {
        IslStatusUpdateNotification trigger = new IslStatusUpdateNotification(
                reference.getSource().getDatapath(), reference.getSource().getPortNumber(),
                reference.getDest().getDatapath(), reference.getDest().getPortNumber(),
                status);
        context.getOutput().islStatusUpdateNotification(trigger);
    }

    private void updateLinkData(Endpoint bind, IslDataHolder data) {
        log.info("ISL {} data update - bind:{} - {}", discoveryFacts.getReference(), bind, data);
        discoveryFacts.put(bind, data);
    }

    private void updateEndpointStatusByEvent(IslFsmEvent event, IslFsmContext context) {
        IslEndpointStatus status = null;
        switch (event) {
            case ISL_UP:
                status = new IslEndpointStatus(IslEndpointStatus.Status.UP);
                break;
            case ISL_DOWN:
                status = new IslEndpointStatus(IslEndpointStatus.Status.DOWN, context.getDownReason());
                break;
            case ISL_MOVE:
                status = new IslEndpointStatus(IslEndpointStatus.Status.MOVED);
                break;
            default:
                log.debug("Ignore status update for not applicable event {} (context={}, state={})",
                          event, context, getCurrentState());
        }

        if (status != null) {
            endpointStatus.put(context.getEndpoint(), status);
        }
    }

    private void loadPersistentData(Endpoint start, Endpoint end) {
        Optional<Isl> potentialIsl = islRepository.findByEndpoints(
                start.getDatapath(), start.getPortNumber(),
                end.getDatapath(), end.getPortNumber());
        if (potentialIsl.isPresent()) {
            Isl isl = potentialIsl.get();

            for (DiscoveryMonitor<?> entry : monitorsByPriority) {
                entry.load(start, isl);
            }
        } else {
            log.error("There is no persistent ISL data {} ==> {} (possible race condition during topology "
                              + "initialisation)", start, end);
        }
    }

    private void triggerAffectedFlowReroute(IslFsmContext context) {
        Endpoint source = discoveryFacts.getReference().getSource();

        String reason = makeRerouteReason(context.getEndpoint(), context.getDownReason());

        // FIXME (surabujin): why do we send only one ISL endpoint here?
        PathNode pathNode = new PathNode(source.getDatapath(), source.getPortNumber(), 0);
        RerouteAffectedFlows trigger = new RerouteAffectedFlows(pathNode, reason);
        context.getOutput().triggerReroute(trigger);
    }

    private void triggerDownFlowReroute(IslFsmContext context) {
        if (shouldEmitDownFlowReroute()) {
            Endpoint source = discoveryFacts.getReference().getSource();
            PathNode pathNode = new PathNode(source.getDatapath(), source.getPortNumber(), 0);
            RerouteInactiveFlows trigger = new RerouteInactiveFlows(pathNode, String.format(
                    "ISL %s status become %s", discoveryFacts.getReference(), IslStatus.ACTIVE));
            context.getOutput().triggerReroute(trigger);
        }
    }

    private void flushTransaction() {
        transactionManager.doInTransaction(transactionRetryPolicy, () -> flush(clock.instant()));
    }

    private void saveStatusAndSetIslUnstableTimeTransaction() {
        transactionManager.doInTransaction(transactionRetryPolicy, () -> {
            Instant timeNow = Instant.now();

            saveStatus(timeNow);
            setIslUnstableTime(timeNow);
        });
    }

    private void flush(Instant timeNow) {
        Socket socket = prepareSocket();
        flush(socket.getSource(), socket.getDest(), timeNow);
        flush(socket.getDest(), socket.getSource(), timeNow);
    }

    private void flush(Anchor source, Anchor dest, Instant timeNow) {
        Isl link = loadOrCreateIsl(source, dest, timeNow);
        long maxBandwidth = link.getMaxBandwidth();
        for (DiscoveryMonitor<?> entry : monitorsByPriority) {
            entry.flush(source.getEndpoint(), link);
        }

        applyIslMaxBandwidth(link, source.getEndpoint(), dest.getEndpoint());
        if (maxBandwidth != link.getMaxBandwidth()) {
            applyIslAvailableBandwidth(link, source.getEndpoint(), dest.getEndpoint());
        }

        link.setStatus(effectiveStatus);
        if (effectiveStatus == IslStatus.INACTIVE) {
            link.setDownReason(downReason);
        } else {
            link.setDownReason(null);
        }

        link.setTimeModify(timeNow);

        pushIslChanges(link);
    }

    private void setIslUnstableTime(Instant timeNow) {
        Socket socket = prepareSocket();
        setIslUnstableTime(socket.getSource(), socket.getDest(), timeNow);
        setIslUnstableTime(socket.getDest(), socket.getSource(), timeNow);
    }

    private void setIslUnstableTime(Anchor source, Anchor dest, Instant timeNow) {
        Isl link = loadOrCreateIsl(source, dest, timeNow);

        log.debug("Set ISL {} ===> {} unstable time due to physical port down", source, dest);

        link.setTimeModify(timeNow);
        link.setTimeUnstable(timeNow);
        pushIslChanges(link);
    }

    private Socket prepareSocket() {
        IslReference reference = discoveryFacts.getReference();
        Anchor source = loadSwitchCreateIfMissing(reference.getSource());
        Anchor dest = loadSwitchCreateIfMissing(reference.getDest());
        switchRepository.lockSwitches(source.getSw(), dest.getSw());

        return new Socket(source, dest);
    }

    private Isl loadOrCreateIsl(Anchor source, Anchor dest, Instant timeNow) {
        return loadIsl(source.getEndpoint(), dest.getEndpoint())
                .orElseGet(() -> createIsl(source, dest, timeNow));
    }

    private Isl createIsl(Anchor source, Anchor dest, Instant timeNow) {
        final Endpoint sourceEndpoint = source.getEndpoint();
        final Endpoint destEndpoint = dest.getEndpoint();
        IslBuilder islBuilder = Isl.builder()
                .timeCreate(timeNow)
                .timeModify(timeNow)
                .srcSwitch(source.getSw())
                .srcPort(sourceEndpoint.getPortNumber())
                .destSwitch(dest.getSw())
                .destPort(destEndpoint.getPortNumber())
                .underMaintenance(source.getSw().isUnderMaintenance() || dest.getSw().isUnderMaintenance());
        initializeFromLinkProps(sourceEndpoint, destEndpoint, islBuilder);
        Isl link = islBuilder.build();

        log.debug("Create new DB object (prefilled): {}", link);
        return link;
    }

    private Anchor loadSwitchCreateIfMissing(Endpoint endpoint) {
        final SwitchId datapath = endpoint.getDatapath();
        Switch sw = switchRepository.findById(datapath)
                .orElseGet(() -> {
                    log.error("Switch {} is missing in DB, create empty switch record", datapath);
                    return createSwitch(datapath);
                });
        return new Anchor(endpoint, sw);
    }

    private Switch createSwitch(SwitchId datapath) {
        Switch sw = Switch.builder()
                .switchId(datapath)
                .status(SwitchStatus.INACTIVE)
                .description(String.format("auto created as part of ISL %s discovery", discoveryFacts.getReference()))
                .build();

        switchRepository.createOrUpdate(sw);

        return sw;
    }

    private Optional<Isl> loadIsl(Endpoint source, Endpoint dest) {
        return islRepository.findByEndpoints(
                source.getDatapath(), source.getPortNumber(),
                dest.getDatapath(), dest.getPortNumber())
                .map(link -> {
                    log.debug("Read ISL object: {}", link);
                    return link;
                });
    }

    private void applyIslMaxBandwidth(Isl link, Endpoint source, Endpoint dest) {
        loadLinkProps(source, dest)
                .ifPresent(props -> applyIslMaxBandwidth(link, props));
    }

    private void applyIslMaxBandwidth(Isl link, LinkProps props) {
        Long maxBandwidth = props.getMaxBandwidth();
        if (maxBandwidth != null) {
            link.setMaxBandwidth(maxBandwidth);
        }
    }

    private void applyIslAvailableBandwidth(Isl link, Endpoint source, Endpoint dest) {
        long usedBandwidth = flowPathRepository.getUsedBandwidthBetweenEndpoints(
                source.getDatapath(), source.getPortNumber(),
                dest.getDatapath(), dest.getPortNumber());
        link.setAvailableBandwidth(link.getMaxBandwidth() - usedBandwidth);
    }

    private void initializeFromLinkProps(Endpoint source, Endpoint dest, IslBuilder isl) {
        Optional<LinkProps> linkProps = loadLinkProps(source, dest);
        if (linkProps.isPresent()) {
            LinkProps entry = linkProps.get();

            Integer cost = entry.getCost();
            if (cost != null) {
                isl.cost(cost);
            }

            Long maxBandwidth = entry.getMaxBandwidth();
            if (maxBandwidth != null) {
                isl.maxBandwidth(maxBandwidth);
            }
        }
    }

    private void pushIslChanges(Isl link) {
        log.debug("Write ISL object: {}", link);
        islRepository.createOrUpdate(link);
    }

    private Optional<LinkProps> loadLinkProps(Endpoint source, Endpoint dest) {
        Collection<LinkProps> storedProps = linkPropsRepository.findByEndpoints(
                source.getDatapath(), source.getPortNumber(),
                dest.getDatapath(), dest.getPortNumber());
        Optional<LinkProps> result = Optional.empty();
        for (LinkProps entry : storedProps) {
            result = Optional.of(entry);
            // We can/should put "break" here but it lead to warnings... Anyway only one match possible
            // by such(full) query so we can avoid "break" here.
        }
        return result;
    }

    private boolean evaluateStatus() {
        IslStatus become = null;
        IslDownReason reason = null;
        for (DiscoveryMonitor<?> entry : monitorsByPriority) {
            Optional<IslStatus> status = entry.evaluateStatus();
            if (status.isPresent()) {
                become = status.get();
                reason = entry.getDownReason();
                break;
            }
        }
        if (become == null) {
            become = IslStatus.INACTIVE;
        }

        boolean isChanged = effectiveStatus != become;
        effectiveStatus = become;
        if (effectiveStatus == IslStatus.INACTIVE) {
            downReason = reason;
        }

        return isChanged;
    }

    private void emitBecomeStateEvent(IslFsmContext context) {
        IslFsmEvent route;
        switch (effectiveStatus) {
            case ACTIVE:
                route = IslFsmEvent._BECOME_UP;
                break;
            case INACTIVE:
                route = IslFsmEvent._BECOME_DOWN;
                break;
            case MOVED:
                route = IslFsmEvent._BECOME_MOVED;
                break;
            default:
                throw new IllegalArgumentException(makeInvalidMappingMessage(
                        effectiveStatus.getClass(), IslFsmEvent.class, effectiveStatus));
        }
        fire(route, context);
    }

    private boolean shouldSetupBfd() {
        // TODO(surabujin): ensure the switch is BFD capable

        IslReference reference = discoveryFacts.getReference();
        return isPerIslBfdToggleEnabled(reference.getSource(), reference.getDest())
                || isPerIslBfdToggleEnabled(reference.getDest(), reference.getSource());
    }

    private boolean isSwitchInMultiTableMode(SwitchId switchId) {
        return switchPropertiesRepository
                .findBySwitchId(switchId)
                .map(SwitchProperties::isMultiTable)
                .orElse(false);
    }

    private boolean isMultiTableManagementCompleted() {
        return endpointMultiTableManagementCompleteStatus.stream()
                .filter(Objects::nonNull)
                .allMatch(entry -> entry);
    }

    private boolean isPerIslBfdToggleEnabled(Endpoint source, Endpoint dest) {
        return loadIsl(source, dest)
                .map(Isl::isEnableBfd)
                .orElseThrow(() -> new PersistenceException(
                        String.format("Isl %s ===> %s record not found in DB", source, dest)));
    }

    // TODO(surabujin): should this check been moved into reroute topology?
    private boolean shouldEmitDownFlowReroute() {
        return featureTogglesRepository.find()
                .map(FeatureToggles::getFlowsRerouteOnIslDiscoveryEnabled)
                .orElse(FeatureToggles.DEFAULTS.getFlowsRerouteOnIslDiscoveryEnabled());
    }

    private String makeRerouteReason(Endpoint endpoint, IslDownReason reason) {
        IslStatus status = mapStatus(evaluateStatus());
        IslReference reference = discoveryFacts.getReference();
        if (reason == null) {
            return String.format("ISL %s status become %s", reference, status);
        }

        String humanReason;
        switch (reason) {
            case PORT_DOWN:
                humanReason = String.format("ISL %s become %s due to physical link DOWN event on %s",
                                            reference, status, endpoint);
                break;
            case POLL_TIMEOUT:
                humanReason = String.format("ISL %s become %s because of FAIL TIMEOUT (endpoint:%s)",
                                            reference, status, endpoint);
                break;
            case BFD_DOWN:
                humanReason = String.format("ISL %s become %s because BFD detect link failure (endpoint:%s)",
                                            reference, status, endpoint);
                break;

            default:
                humanReason = String.format("ISL %s become %s (endpoint:%s, reason:%s)",
                                            reference, status, endpoint, reason);
        }

        return humanReason;
    }

    private static String makeInvalidMappingMessage(Class<?> from, Class<?> to, Object value) {
        return String.format("There is no mapping defined between %s and %s for %s", from.getName(),
                             to.getName(), value);
    }

    @Value
    private static class Anchor {
        Endpoint endpoint;
        Switch sw;
    }

    @Value
    private static class Socket {
        Anchor source;
        Anchor dest;
    }

    // FIXME - }}}
    public static class IslFsmFactory {
        private final Clock clock;

        private final NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder;

        private final PersistenceManager persistenceManager;
        private final StateMachineBuilder<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> builder;

        IslFsmFactory(Clock clock, PersistenceManager persistenceManager,
                      NetworkTopologyDashboardLogger.Builder dashboardLoggerBuilder) {
            this.clock = clock;

            this.persistenceManager = persistenceManager;
            this.dashboardLoggerBuilder = dashboardLoggerBuilder;

            builder = StateMachineBuilderFactory.create(
                    IslFsm.class, IslFsmState.class, IslFsmEvent.class, IslFsmContext.class,
                    // extra parameters
                    Clock.class, PersistenceManager.class, NetworkTopologyDashboardLogger.class, BfdManager.class,
                    NetworkOptions.class, IslReference.class);

            final String updateMonitorsActionMethod = "updateMonitorsAction";
            final String flushActionMethod = "flushAction";

            // OPERATIONAL
            builder.defineSequentialStatesOn(
                    IslFsmState.OPERATIONAL,
                    IslFsmState.PENDING,
                    IslFsmState.ACTIVE, IslFsmState.INACTIVE, IslFsmState.MOVED,
                    IslFsmState.SET_UP_RESOURCES);

            builder.transition()
                    .from(IslFsmState.OPERATIONAL).to(IslFsmState.CLEAN_UP_RESOURCES)
                    .on(IslFsmEvent._REMOVE_CONFIRMED);
            builder.onEntry(IslFsmState.OPERATIONAL)
                    .callMethod("loadPersistedState");
            builder.internalTransition().within(IslFsmState.OPERATIONAL).on(IslFsmEvent.ISL_UP)
                    .callMethod(updateMonitorsActionMethod);
            builder.internalTransition().within(IslFsmState.OPERATIONAL).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(updateMonitorsActionMethod);
            builder.internalTransition().within(IslFsmState.OPERATIONAL).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateMonitorsActionMethod);
            builder.internalTransition().within(IslFsmState.OPERATIONAL).on(IslFsmEvent.BFD_UP)
                    .callMethod(updateMonitorsActionMethod);
            builder.internalTransition().within(IslFsmState.OPERATIONAL).on(IslFsmEvent.BFD_DOWN)
                    .callMethod(updateMonitorsActionMethod);
            builder.internalTransition().within(IslFsmState.OPERATIONAL).on(IslFsmEvent.BFD_KILL)
                    .callMethod(updateMonitorsActionMethod);
            builder.internalTransition().within(IslFsmState.OPERATIONAL).on(IslFsmEvent.ROUND_TRIP_STATUS)
                    .callMethod(updateMonitorsActionMethod);
            builder.internalTransition().within(IslFsmState.OPERATIONAL).on(IslFsmEvent.ISL_REMOVE)
                    .callMethod("removeAttempt");

            // PENDING
            builder.transition()
                    .from(IslFsmState.PENDING).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent._BECOME_UP);
            builder.transition()
                    .from(IslFsmState.PENDING).to(IslFsmState.INACTIVE).on(IslFsmEvent._BECOME_DOWN);
            builder.transition()
                    .from(IslFsmState.PENDING).to(IslFsmState.MOVED).on(IslFsmEvent._BECOME_MOVED);
            builder.internalTransition().within(IslFsmState.PENDING).on(IslFsmEvent._FLUSH)
                    .callMethod(flushActionMethod);

            // SET_UP_RESOURCES
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.ACTIVE).on(IslFsmEvent._RESOURCES_DONE);
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.INACTIVE).on(IslFsmEvent._BECOME_DOWN);
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.MOVED).on(IslFsmEvent._BECOME_MOVED);
            builder.onEntry(IslFsmState.SET_UP_RESOURCES)
                    .callMethod("setUpResourcesEnter");
            builder.internalTransition()
                    .within(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent.ISL_RULE_INSTALLED)
                    .callMethod("handleInstalledRule");
            builder.internalTransition()
                    .within(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent.ISL_RULE_TIMEOUT)
                    .callMethod("setUpResourcesTimeout");
            builder.internalTransition().within(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent._FLUSH)
                    .callMethod(flushActionMethod);

            // ACTIVE
            builder.transition()
                    .from(IslFsmState.ACTIVE).to(IslFsmState.INACTIVE).on(IslFsmEvent._BECOME_DOWN);
            builder.transition()
                    .from(IslFsmState.ACTIVE).to(IslFsmState.MOVED).on(IslFsmEvent._BECOME_MOVED);
            builder.onEntry(IslFsmState.ACTIVE)
                    .callMethod("activeEnter");
            builder.internalTransition().within(IslFsmState.ACTIVE).on(IslFsmEvent._FLUSH)
                    .callMethod(flushActionMethod);

            // INACTIVE
            builder.transition()
                    .from(IslFsmState.INACTIVE).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent._BECOME_UP);
            builder.transition()
                    .from(IslFsmState.INACTIVE).to(IslFsmState.MOVED).on(IslFsmEvent._BECOME_MOVED);
            builder.onEntry(IslFsmState.INACTIVE)
                    .callMethod("inactiveEnter");
            builder.internalTransition().within(IslFsmState.INACTIVE).on(IslFsmEvent._FLUSH)
                    .callMethod(flushActionMethod);

            // MOVED
            builder.transition()
                    .from(IslFsmState.MOVED).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent._BECOME_UP);
            builder.onEntry(IslFsmState.MOVED)
                    .callMethod("movedEnter");
            builder.internalTransition().within(IslFsmState.MOVED).on(IslFsmEvent._FLUSH)
                    .callMethod(flushActionMethod);

            // CLEAN_UP_RESOURCES
            builder.transition()
                    .from(IslFsmState.CLEAN_UP_RESOURCES).to(IslFsmState.DELETED).on(IslFsmEvent._RESOURCES_DONE);
            builder.onEntry(IslFsmState.CLEAN_UP_RESOURCES).callMethod("cleanUpResourcesEnter");
            builder.internalTransition()
                    .within(IslFsmState.CLEAN_UP_RESOURCES).on(IslFsmEvent.ISL_RULE_REMOVED)
                    .callMethod("handleRemovedRule");
            builder.internalTransition()
                    .within(IslFsmState.CLEAN_UP_RESOURCES).on(IslFsmEvent.ISL_RULE_TIMEOUT)
                    .callMethod("cleanUpResourcesTimeout");

            // DELETED
            builder.defineFinalState(IslFsmState.DELETED);

            // FIXME - {{{
            String updateEndpointStatusMethod = "updateEndpointStatus";
            String updateAndPersistEndpointStatusMethod = "updateAndPersistEndpointStatus";

            // INIT
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_UP)
                    .callMethod("handleInitialDiscovery");
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(updateAndPersistEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateAndPersistEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.DOWN).on(IslFsmEvent._HISTORY_DOWN);
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent._HISTORY_UP)
                    .callMethod("historyRestoreUp");
            builder.transition()
                    .from(IslFsmState.INIT).to(IslFsmState.MOVED).on(IslFsmEvent._HISTORY_MOVED);
            builder.internalTransition()
                    .within(IslFsmState.INIT).on(IslFsmEvent.HISTORY)
                    .callMethod("handleHistory");

            // DOWN
            builder.transition()
                    .from(IslFsmState.DOWN).to(IslFsmState.UP_ATTEMPT).on(IslFsmEvent.ISL_UP)
                    .callMethod(updateEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.DOWN).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateEndpointStatusMethod);
            builder.internalTransition()
                    .within(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(updateAndPersistEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.DOWN).to(IslFsmState.CLEAN_UP_RESOURCES).on(IslFsmEvent._ISL_REMOVE_SUCCESS);

            // UP_ATTEMPT
            builder.transition()
                    .from(IslFsmState.UP_ATTEMPT).to(IslFsmState.DOWN).on(IslFsmEvent._UP_ATTEMPT_FAIL);
            builder.transition()
                    .from(IslFsmState.UP_ATTEMPT).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent._UP_ATTEMPT_SUCCESS);
            builder.onEntry(IslFsmState.UP_ATTEMPT)
                    .callMethod("handleUpAttempt");

            // SET_UP_RESOURCES
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN);
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateEndpointStatusMethod);
            builder.transition()
                    .from(IslFsmState.SET_UP_RESOURCES).to(IslFsmState.UP).on(IslFsmEvent.ISL_UP);


            // UP
            final String upHandlePollEventMethod = "upHandlePollEvent";

            builder.transition()
                    .from(IslFsmState.UP).to(IslFsmState.DOWN).on(IslFsmEvent._BECOME_DOWN);
            builder.onEntry(IslFsmState.UP)
                    .callMethod("upEnter");
            builder.transition()
                    .from(IslFsmState.UP).to(IslFsmState.DOWN).on(IslFsmEvent.ISL_DOWN)
                    .when(new Condition<IslFsmContext>() {
                        @Override
                        public boolean isSatisfied(IslFsmContext context) {
                            return IslDownReason.PORT_DOWN.equals(context.getDownReason());
                        }

                        @Override
                        public String name() {
                            return "isl-down-by-physical-port-down";
                        }
                    })
                    .callMethod("upHandlePhysicalDown");
            builder.transition()
                    .from(IslFsmState.UP).to(IslFsmState.MOVED).on(IslFsmEvent.ISL_MOVE)
                    .callMethod(updateEndpointStatusMethod);
            // FIXME(surabujin): missing on FSM diagram (review whole diagram and sync code and diagram to each other)
            builder.transition()
                    .from(IslFsmState.UP).to(IslFsmState.SET_UP_RESOURCES).on(IslFsmEvent.ISL_MULTI_TABLE_MODE_UPDATED);
            builder.internalTransition().within(IslFsmState.UP).on(IslFsmEvent.ISL_UP)
                    .callMethod(upHandlePollEventMethod);
            builder.internalTransition().within(IslFsmState.UP).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(upHandlePollEventMethod);
            builder.internalTransition().within(IslFsmState.UP).on(IslFsmEvent.ROUND_TRIP_STATUS)
                    .callMethod("upHandleRoundTripStatus");
            builder.onExit(IslFsmState.UP)
                    .callMethod("upExit");

            // MOVED
            builder.transition()
                    .from(IslFsmState.MOVED).to(IslFsmState.UP_ATTEMPT).on(IslFsmEvent.ISL_UP)
                    .callMethod(updateEndpointStatusMethod);
            builder.internalTransition()
                    .within(IslFsmState.MOVED).on(IslFsmEvent.ISL_DOWN)
                    .callMethod(updateAndPersistEndpointStatusMethod);
            builder.internalTransition()
                    .within(IslFsmState.MOVED).on(IslFsmEvent.ISL_REMOVE)
                    .callMethod("removeAttempt");
            builder.transition()
                    .from(IslFsmState.MOVED).to(IslFsmState.CLEAN_UP_RESOURCES).on(IslFsmEvent._ISL_REMOVE_SUCCESS);
            builder.onEntry(IslFsmState.MOVED)
                    .callMethod("movedEnter");
            // FIXME - }}}
        }

        public FsmExecutor<IslFsm, IslFsmState, IslFsmEvent, IslFsmContext> produceExecutor() {
            return new FsmExecutor<>(IslFsmEvent.NEXT);
        }

        /**
         * Create and properly initialize new {@link IslFsm}.
         */
        public IslFsm produce(BfdManager bfdManager, NetworkOptions options, IslReference reference) {
            IslFsm fsm = builder.newStateMachine(
                    IslFsmState.OPERATIONAL, clock, persistenceManager, dashboardLoggerBuilder.build(log), bfdManager,
                    options, reference);
            fsm.start();
            return fsm;
        }
    }

    @Value
    @Builder
    public static class IslFsmContext {
        private final IIslCarrier output;
        private final Endpoint endpoint;

        // FIXME - {{{

        private Isl history;
        private IslDataHolder islData;

        private IslDownReason downReason;
        private Endpoint installedRulesEndpoint;  // FIXME - garbage - must use `endpoint`
        private Endpoint removedRulesEndpoint;    // FIXME - garbage - must use `endpoint`
        private Boolean bfdEnable;

        private RoundTripStatus roundTripStatus;
        // FIXME - }}}

        /**
         * .
         */
        public static IslFsmContextBuilder builder(IIslCarrier output, Endpoint endpoint) {
            return new IslFsmContextBuilder()
                    .output(output)
                    .endpoint(endpoint);
        }
    }

    public enum IslFsmEvent {
        NEXT,
        _FLUSH,
        _BECOME_UP, _BECOME_DOWN, _BECOME_MOVED,
        _RESOURCES_DONE,
        _REMOVE_CONFIRMED,
        BFD_UP, BFD_DOWN, BFD_KILL,  // TODO - inject

        // FIXME - {{{
        HISTORY, _HISTORY_DOWN, _HISTORY_UP, _HISTORY_MOVED,
        ISL_UP, ISL_DOWN, ISL_MOVE, ROUND_TRIP_STATUS,

        _UP_ATTEMPT_SUCCESS, ISL_REMOVE, ISL_REMOVE_FINISHED, _UP_ATTEMPT_FAIL,
        ISL_RULE_INSTALLED,
        ISL_RULE_REMOVED,
        ISL_RULE_TIMEOUT,
        ISL_MULTI_TABLE_MODE_UPDATED,
        // FIXME - }}}
    }

    public enum IslFsmState {
        OPERATIONAL,
        PENDING, ACTIVE, INACTIVE, MOVED,
        SET_UP_RESOURCES, CLEAN_UP_RESOURCES,
        DELETED,
    }
}
