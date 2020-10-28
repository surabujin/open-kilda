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

package org.openkilda.wfm.topology.network.controller.bfd;

import org.openkilda.messaging.floodlight.response.BfdSessionResponse;
import org.openkilda.messaging.model.NoviBfdSession;
import org.openkilda.messaging.model.SwitchReference;
import org.openkilda.model.BfdProperties;
import org.openkilda.model.BfdSession;
import org.openkilda.model.Switch;
import org.openkilda.model.SwitchId;
import org.openkilda.persistence.PersistenceManager;
import org.openkilda.persistence.exceptions.ConstraintViolationException;
import org.openkilda.persistence.repositories.BfdSessionRepository;
import org.openkilda.persistence.repositories.RepositoryFactory;
import org.openkilda.persistence.repositories.SwitchRepository;
import org.openkilda.persistence.tx.TransactionCallback;
import org.openkilda.persistence.tx.TransactionManager;
import org.openkilda.wfm.share.model.Endpoint;
import org.openkilda.wfm.share.utils.AbstractBaseFsm;
import org.openkilda.wfm.share.utils.FsmExecutor;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.BfdSessionFsmContext;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.Event;
import org.openkilda.wfm.topology.network.controller.bfd.BfdSessionFsm.State;
import org.openkilda.wfm.topology.network.error.SwitchReferenceLookupException;
import org.openkilda.wfm.topology.network.model.BfdDescriptor;
import org.openkilda.wfm.topology.network.model.BfdSessionData;
import org.openkilda.wfm.topology.network.model.LinkStatus;
import org.openkilda.wfm.topology.network.service.IBfdSessionCarrier;
import org.openkilda.wfm.topology.network.utils.EndpointStatusListener;
import org.openkilda.wfm.topology.network.utils.EndpointStatusMonitor;
import org.openkilda.wfm.topology.network.utils.SwitchOnlineStatusListener;
import org.openkilda.wfm.topology.network.utils.SwitchOnlineStatusMonitor;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.squirrelframework.foundation.fsm.StateMachineBuilder;
import org.squirrelframework.foundation.fsm.StateMachineBuilderFactory;
import org.squirrelframework.foundation.fsm.StateMachineLogger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

@Slf4j
public final class BfdSessionFsm
        extends AbstractBaseFsm<BfdSessionFsm, State, Event, BfdSessionFsmContext>
        implements SwitchOnlineStatusListener, EndpointStatusListener {
    static final int BFD_UDP_PORT = 3784;

    private final Random random = new Random();

    private final TransactionManager transactionManager;
    private final SwitchRepository switchRepository;
    private final BfdSessionRepository bfdSessionRepository;

    private final IBfdSessionCarrier carrier;

    @Getter
    private final BfdSessionData sessionData;
    @Getter
    private final Endpoint logicalEndpoint;
    private final int physicalPortNumber;

    @Getter
    private BfdProperties properties;
    private Integer discriminator;

    private BfdSessionAction action = null;

    private boolean online;
    private LinkStatus endpointStatus;

    private boolean tearingDown;
    @Getter
    private boolean error;

    public static BfdSessionFsmFactory factory(
            PersistenceManager persistenceManager, SwitchOnlineStatusMonitor switchOnlineStatusMonitor,
            EndpointStatusMonitor endpointStatusMonitor, IBfdSessionCarrier carrier) {
        return new BfdSessionFsmFactory(persistenceManager, switchOnlineStatusMonitor, endpointStatusMonitor, carrier);
    }

    public BfdSessionFsm(
            PersistenceManager persistenceManager,
            SwitchOnlineStatusMonitor switchOnlineStatusMonitor, EndpointStatusMonitor endpointStatusMonitor,
            IBfdSessionCarrier carrier, BfdSessionData sessionData, Endpoint logical, Integer physicalPortNumber) {
        transactionManager = persistenceManager.getTransactionManager();

        RepositoryFactory repositoryFactory = persistenceManager.getRepositoryFactory();
        this.switchRepository = repositoryFactory.createSwitchRepository();
        this.bfdSessionRepository = repositoryFactory.createBfdSessionRepository();

        this.carrier = carrier;
        this.sessionData = sessionData;

        this.logicalEndpoint = logical;
        this.physicalPortNumber = physicalPortNumber;

        online = switchOnlineStatusMonitor.subscribe(logicalEndpoint.getDatapath(), this);
        endpointStatus = endpointStatusMonitor.subscribe(logicalEndpoint, this);
    }

    // -- external API --

    public boolean enableIfReady() {
        if (tearingDown) {
            return false;
        }
        if (this.properties != null && ! this.properties.equals(sessionData.getProperties())) {
            return false;
        }

        internalFire(Event.ENABLE);
        return true;
    }

    public void disableIfConfigured() {
        if (properties != null) {
            BfdSessionFsmContext context = BfdSessionFsmContext.builder().build();
            BfdSessionFsmFactory.EXECUTOR.fire(this, Event.DISABLE, context);
        }
    }

    public void speakerResponse(String key) {
        speakerResponse(key, null);  // timeout
    }

    public void speakerResponse(String key, BfdSessionResponse response) {
        if (action != null) {
            action.consumeSpeakerResponse(key, response)
                    .ifPresent(result -> handleActionResult(result, response));
        }
    }

    @Override
    public void switchOnlineStatusUpdate(boolean isOnline) {
        online = isOnline;

        internalFire(isOnline ? Event.ONLINE : Event.OFFLINE);
    }

    @Override
    public void endpointStatusUpdate(LinkStatus status) {
        endpointStatus = status;
        pullPortStatus();
    }

    // -- FSM actions --

    public void enterEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        loadExistingSession();
    }

    public void prepareEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        Optional<BfdDescriptor> descriptor = allocateDiscriminator(context);
        if (online && descriptor.isPresent()) {
            action = new BfdSessionCreateAction(carrier, makeBfdSessionRecord(descriptor.get()));
            internalFire(Event.READY, context);
        }
    }

    public void sendSessionCreateRequestAction(State from, State to, Event event, BfdSessionFsmContext context) {
        Optional<BfdDescriptor> descriptor = makeSessionDescriptor(context);
        if (descriptor.isPresent()) {
            action = new BfdSessionRemoveAction(carrier, makeBfdSessionRecord(descriptor.get()));
            internalFire(Event.READY, context);
        }
    }

    public void creatingEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        endpointStatus = null;
    }

    public void activeEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("BFD session is operational");
        saveEffectiveProperties();
    }

    public void offlineIntoActiveAction(State from, State to, Event event, BfdSessionFsmContext context) {
        carrier.bfdKillNotification(makePhysicalEndpoint());
        endpointStatus = null;
    }

    public void activeExitAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("notify consumer(s) to STOP react on BFD event");
        carrier.bfdKillNotification(makePhysicalEndpoint());
    }

    public void waitStatusEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        pullPortStatus();
    }

    public void upEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("LINK detected");
        carrier.bfdUpNotification(makePhysicalEndpoint());
    }

    public void downEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        logInfo("LINK corrupted");
        carrier.bfdDownNotification(makePhysicalEndpoint());
    }

    public void removingEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        tearingDown = true;
        makeSessionRemoveAction(context);
    }

    public void sessionRemoveAction(State from, State to, Event event, BfdSessionFsmContext context) {
        makeSessionRemoveAction(context);
    }

    public void errorEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        error = true;
        carrier.bfdFailNotification(makePhysicalEndpoint());
    }

    public void housekeepingEnterAction(State from, State to, Event event, BfdSessionFsmContext context) {
        releaseDiscriminator();
    }

    // -- private/service methods --

    private void loadExistingSession() {
        transactionManager.doInTransaction(() -> loadBfdSession().ifPresent(this::loadExistingSession));
    }

    private void loadExistingSession(BfdSession dbView) {
        discriminator = dbView.getDiscriminator();
        properties = BfdProperties.builder()
                .interval(dbView.getInterval())
                .multiplier(dbView.getMultiplier())
                .build();
    }

    private void makeSessionRemoveAction(BfdSessionFsmContext context) {
        if (online) {
            makeSessionDescriptor(context).ifPresent(
                    descriptor -> action = new BfdSessionRemoveAction(carrier, makeBfdSessionRecord(descriptor)));
        }
    }

    private NoviBfdSession makeBfdSessionRecord(BfdDescriptor descriptor) {
        if (discriminator == null) {
            throw new IllegalStateException(makeLogPrefix() + " there is no allocated discriminator");
        }

        BfdProperties properties = sessionData.getProperties();
        return NoviBfdSession.builder()
                .target(descriptor.getLocal())
                .remote(descriptor.getRemote())
                .physicalPortNumber(physicalPortNumber)
                .logicalPortNumber(logicalEndpoint.getPortNumber())
                .udpPortNumber(BFD_UDP_PORT)
                .discriminator(discriminator)
                .keepOverDisconnect(true)
                .intervalMs((int) properties.getInterval().toMillis())
                .multiplier(properties.getMultiplier())
                .build();
    }

    private Optional<BfdDescriptor> allocateDiscriminator(BfdSessionFsmContext context) {
        Optional<BfdDescriptor> descriptor = makeSessionDescriptor(context);
        descriptor.ifPresent(this::allocateDiscriminator);
        return descriptor;
    }

    private void allocateDiscriminator(BfdDescriptor descriptor) {
        BfdSession dbView;
        while (true) {
            try {
                dbView = transactionManager.doInTransaction(() -> {
                    BfdSession bfdSession = loadBfdSession().orElse(null);
                    if (bfdSession == null || bfdSession.getDiscriminator() == null) {
                        // FIXME(surabujin): loop will never end if all possible discriminators are allocated
                        int discriminator = random.nextInt();
                        if (bfdSession != null) {
                            bfdSession.setDiscriminator(discriminator);
                            descriptor.fill(bfdSession);
                        } else {
                            bfdSession = BfdSession.builder()
                                    .switchId(logicalEndpoint.getDatapath())
                                    .port(logicalEndpoint.getPortNumber())
                                    .physicalPort(physicalPortNumber)
                                    .discriminator(discriminator)
                                    .build();
                            descriptor.fill(bfdSession);
                            bfdSessionRepository.add(bfdSession);
                        }
                    }
                    return bfdSession;
                });
                break;
            } catch (ConstraintViolationException ex) {
                log.warn("ConstraintViolationException on allocate bfd discriminator");
            }
        }

        descriptor.toBuilder()
                .discriminator(dbView.getDiscriminator())
                .build();
    }

    private void releaseDiscriminator() {
        if (discriminator == null) {
            return;
        }

        transactionManager.doInTransaction(() -> {
            bfdSessionRepository
                    .findBySwitchIdAndPort(logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber())
                    .ifPresent(this::releaseDiscriminator);
        });
    }

    private void releaseDiscriminator(BfdSession session) {
        if (Objects.equals(session.getDiscriminator(), discriminator)) {
            bfdSessionRepository.remove(session);
        }
    }

    private void saveEffectiveProperties() {
        properties = sessionData.getProperties();
        transactionManager.doInTransaction(this::saveEffectivePropertiesTransaction);
    }

    private void saveEffectivePropertiesTransaction() {
        Optional<BfdSession> session = loadBfdSession();
        if (session.isPresent()) {
            BfdSession dbView = session.get();
            dbView.setInterval(properties.getInterval());
            dbView.setMultiplier(properties.getMultiplier());
        } else {
            logError("DB session is missing, unable to save effective properties values");
        }
    }

    private Optional<BfdSession> loadBfdSession() {
        return bfdSessionRepository.findBySwitchIdAndPort(
                logicalEndpoint.getDatapath(), logicalEndpoint.getPortNumber());
    }

    private Optional<BfdDescriptor> makeSessionDescriptor(BfdSessionFsmContext context) {
        BfdDescriptor descriptor = null;
        try {
            descriptor = transactionManager.doInTransaction(
                    (TransactionCallback<BfdDescriptor, SwitchReferenceLookupException>) this::makeSessionDescriptor);
        } catch (SwitchReferenceLookupException e) {
            logError(e.getMessage());
            internalFire(Event.ERROR, context);
        }
        return Optional.ofNullable(descriptor);
    }

    private BfdDescriptor makeSessionDescriptor() throws SwitchReferenceLookupException {
        Endpoint remoteEndpoint = sessionData.getReference().getOpposite(makePhysicalEndpoint());
        return BfdDescriptor.builder()
                .local(makeSwitchReference(logicalEndpoint.getDatapath()))
                .remote(makeSwitchReference(remoteEndpoint.getDatapath()))
                .build();
    }

    private SwitchReference makeSwitchReference(SwitchId datapath) throws SwitchReferenceLookupException {
        Switch sw = switchRepository.findById(datapath)
                .orElseThrow(() -> new SwitchReferenceLookupException(datapath, "persistent record is missing"));
        return new SwitchReference(datapath, sw.getSocketAddress().getAddress());
    }

    private SwitchReference makeSwitchReference(SwitchId datapath, String ipAddress)
            throws SwitchReferenceLookupException {
        if (ipAddress == null) {
            throw new SwitchReferenceLookupException(datapath, "null switch address is provided");
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(ipAddress);
        } catch (UnknownHostException e) {
            throw new SwitchReferenceLookupException(
                    datapath,
                    String.format("unable to parse switch address \"%s\"", ipAddress));
        }

        return new SwitchReference(datapath, address);
    }

    private void pullPortStatus() {
        if (endpointStatus == null) {
            return;
        }

        Event event;
        switch (endpointStatus) {
            case UP:
                event = Event.PORT_UP;
                break;
            case DOWN:
                event = Event.PORT_DOWN;
                break;

            default:
                throw new IllegalStateException(String.format(
                        "%s - there is no mapping from %s.%s into %s",
                        makeLogPrefix(), endpointStatus.getClass().getName(), endpointStatus, Event.class.getName()));
        }
        internalFire(event);
    }

    private void handleActionResult(BfdSessionAction.ActionResult result, BfdSessionResponse response) {
        Event event;
        if (result.isSuccess()) {
            event = Event.ACTION_SUCCESS;
        } else {
            event = Event.ACTION_FAIL;
            reportActionFailure(result);
        }

        action = null;
        BfdSessionFsmContext context = BfdSessionFsmContext.builder()
                .speakerResponse(response)
                .build();
        internalFire(event, context);
    }

    private void reportActionFailure(BfdSessionAction.ActionResult result) {
        String prefix = String.format("%s action have FAILED", action.getLogIdentifier());
        if (result.getErrorCode() == null) {
            logError(String.format("%s due to TIMEOUT on speaker request", prefix));
        } else {
            logError(String.format("%s with error %s", prefix, result.getErrorCode()));
        }
    }

    private Endpoint makePhysicalEndpoint() {
        return Endpoint.of(logicalEndpoint.getDatapath(), physicalPortNumber);
    }

    private void internalFire(Event event) {
        internalFire(event, BfdSessionFsmContext.builder().build());
    }

    private void internalFire(Event event, BfdSessionFsmContext context) {
        BfdSessionFsmFactory.EXECUTOR.fire(this, event, context);
    }

    private void logInfo(String message, Object... args) {
        if (log.isInfoEnabled()) {
            log.info("{} - " + message, makeLogPrefix(), args);
        }
    }

    private void logError(String message, Object... args) {
        if (log.isErrorEnabled()) {
            log.error("{} - " + message, makeLogPrefix(), args);
        }
    }

    private String makeLogPrefix() {
        return String.format("BFD session %s(physical-port:%s)", logicalEndpoint, physicalPortNumber);
    }

    public static class BfdSessionFsmFactory {
        public static final FsmExecutor<BfdSessionFsm, State, Event, BfdSessionFsmContext> EXECUTOR
                = new FsmExecutor<>(Event.NEXT);

        private final PersistenceManager persistenceManager;
        private final SwitchOnlineStatusMonitor switchOnlineStatusMonitor;
        private final EndpointStatusMonitor endpointStatusMonitor;

        @Getter
        private final IBfdSessionCarrier carrier;

        private final StateMachineBuilder<BfdSessionFsm, State, Event, BfdSessionFsmContext> builder;

        BfdSessionFsmFactory(
                PersistenceManager persistenceManager, SwitchOnlineStatusMonitor switchOnlineStatusMonitor,
                EndpointStatusMonitor endpointStatusMonitor, IBfdSessionCarrier carrier) {
            this.persistenceManager = persistenceManager;
            this.switchOnlineStatusMonitor = switchOnlineStatusMonitor;
            this.endpointStatusMonitor = endpointStatusMonitor;
            this.carrier = carrier;

            builder = StateMachineBuilderFactory.create(
                    BfdSessionFsm.class, State.class, Event.class, BfdSessionFsmContext.class,
                    // extra parameters
                    PersistenceManager.class, SwitchOnlineStatusMonitor.class, EndpointStatusMonitor.class,
                    IBfdSessionCarrier.class, BfdSessionData.class, Endpoint.class, Integer.class);

            final String sessionRemoveAction = "sessionRemoveAction";

            // ENTER
            builder.onEntry(State.ENTER)
                    .callMethod("enterEnterAction");
            builder.transition()
                    .from(State.ENTER).to(State.PREPARE).on(Event.ENABLE);
            builder.transition().from(
                    State.ENTER).to(State.REMOVING).on(Event.DISABLE);

            // PREPARE
            builder.onEntry(State.PREPARE)
                    .callMethod("prepareEnterAction");
            builder.transition()
                    .from(State.PREPARE).to(State.CREATING).on(Event.READY);
            builder.transition()
                    .from(State.PREPARE).to(State.HOUSEKEEPING).on(Event.DISABLE);
            builder.transition()
                    .from(State.PREPARE).to(State.ERROR).on(Event.ERROR);
            builder.internalTransition()
                    .within(State.PREPARE).on(Event.ONLINE)
                    .callMethod("sendSessionCreateRequestAction");

            // CREATING
            builder.onEntry(State.CREATING)
                    .callMethod("creatingEnterAction");
            builder.transition()
                    .from(State.CREATING).to(State.ACTIVE).on(Event.ACTION_SUCCESS);
            builder.transition()
                    .from(State.CREATING).to(State.ERROR).on(Event.ACTION_FAIL);
            builder.transition()
                    .from(State.CREATING).to(State.REMOVING).on(Event.DISABLE);

            // ACTIVE
            builder.onEntry(State.ACTIVE)
                    .callMethod("activeEnterAction");
            builder.transition()
                    .from(State.ACTIVE).to(State.REMOVING).on(Event.DISABLE);
            builder.internalTransition()
                    .within(State.ACTIVE).on(Event.OFFLINE)
                    .callMethod("offlineIntoActiveAction");
            builder.onExit(State.ACTIVE)
                    .callMethod("activeExitAction");

            builder.defineSequentialStatesOn(
                    State.ACTIVE,
                    State.WAIT_STATUS, State.UP, State.DOWN);

            // WAIT_STATUS
            builder.onEntry(State.WAIT_STATUS)
                    .callMethod("waitStatusEnterAction");
            builder.transition()
                    .from(State.WAIT_STATUS).to(State.UP).on(Event.PORT_UP);
            builder.transition()
                    .from(State.WAIT_STATUS).to(State.DOWN).on(Event.PORT_DOWN);

            // UP
            builder.transition()
                    .from(State.UP).to(State.DOWN).on(Event.PORT_DOWN);
            builder.onEntry(State.UP)
                    .callMethod("upEnterAction");

            // DOWN
            builder.transition()
                    .from(State.DOWN).to(State.UP).on(Event.PORT_UP);
            builder.onEntry(State.DOWN)
                    .callMethod("downEnterAction");

            // REMOVING
            builder.onEntry(State.REMOVING)
                    .callMethod("removingEnterAction");
            builder.transition()
                    .from(State.REMOVING).to(State.HOUSEKEEPING).on(Event.ACTION_SUCCESS);
            builder.internalTransition()
                    .within(State.REMOVING).on(Event.ONLINE)
                    .callMethod(sessionRemoveAction);
            builder.internalTransition()
                    .within(State.REMOVING).on(Event.DISABLE)
                    .callMethod(sessionRemoveAction);

            // ERROR
            builder.onEntry(State.ERROR)
                    .callMethod("errorEnterAction");
            builder.transition()
                    .from(State.ERROR).to(State.HOUSEKEEPING).on(Event.NEXT);

            // HOUSEKEEPING
            builder.onEntry(State.HOUSEKEEPING)
                    .callMethod("housekeepingEnterAction");
            builder.transition()
                    .from(State.HOUSEKEEPING).to(State.STOP).on(Event.NEXT);

            // STOP
            builder.defineFinalState(State.STOP);
        }

        public BfdSessionFsm produce(BfdSessionData sessionData, Endpoint logical, Integer physicalPortNumber) {
            BfdSessionFsm entity = builder.newStateMachine(
                    State.ENTER, persistenceManager, switchOnlineStatusMonitor, endpointStatusMonitor, carrier,
                    sessionData, logical, physicalPortNumber);
            // FIXME - DEBUG!
            new StateMachineLogger(entity).startLogging();
            // FIXME - DEBUG!
            return entity;
        }
    }

    @Value
    @Builder
    public static class BfdSessionFsmContext {
        BfdSessionResponse speakerResponse;
    }

    public enum Event {
        NEXT, ERROR, READY,
        ENABLE, DISABLE,

        ACTION_SUCCESS, ACTION_FAIL,

        ONLINE, OFFLINE,
        PORT_UP, PORT_DOWN,
    }

    public enum State {
        ENTER,
        PREPARE,
        CREATING,
        ACTIVE, WAIT_STATUS, UP, DOWN,
        REMOVING,
        ERROR,
        HOUSEKEEPING,
        STOP
    }
}
