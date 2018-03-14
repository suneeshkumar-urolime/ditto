/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.connectivity.messaging;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;
import static org.eclipse.ditto.services.models.connectivity.ConnectivityMessagingConstants.CLUSTER_ROLE;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionStatus;
import org.eclipse.ditto.model.connectivity.MappingContext;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.services.connectivity.messaging.persistence.ConnectionData;
import org.eclipse.ditto.services.connectivity.messaging.persistence.ConnectionMongoSnapshotAdapter;
import org.eclipse.ditto.services.connectivity.util.ConfigKeys;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.services.utils.persistence.SnapshotAdapter;
import org.eclipse.ditto.signals.commands.connectivity.ConnectivityCommand;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionConflictException;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionFailedException;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionNotAccessibleException;
import org.eclipse.ditto.signals.commands.connectivity.modify.CloseConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.CloseConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.DeleteConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.DeleteConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.modify.OpenConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.OpenConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnection;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionStatus;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionStatusResponse;
import org.eclipse.ditto.signals.commands.base.Command;
import org.eclipse.ditto.signals.events.connectivity.ConnectionClosed;
import org.eclipse.ditto.signals.events.connectivity.ConnectionCreated;
import org.eclipse.ditto.signals.events.connectivity.ConnectionDeleted;
import org.eclipse.ditto.signals.events.connectivity.ConnectionOpened;
import org.eclipse.ditto.signals.events.base.Event;
import org.eclipse.ditto.signals.events.things.ThingEvent;

import com.typesafe.config.Config;

import akka.ConfigurationException;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Status;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.cluster.routing.ClusterRouterPool;
import akka.cluster.routing.ClusterRouterPoolSettings;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.PatternsCS;
import akka.persistence.AbstractPersistentActor;
import akka.persistence.RecoveryCompleted;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotOffer;
import akka.routing.Broadcast;
import akka.routing.RoundRobinPool;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

/**
 * Handles {@code *Connection} commands and manages the persistence of connection. The actual connection handling to the
 * remote server is delegated to a child actor that uses a specific client (AMQP 1.0 or 0.9.1).
 */
final class ConnectionActor extends AbstractPersistentActor {

    private static final String PERSISTENCE_ID_PREFIX = "connection:";

    private static final String JOURNAL_PLUGIN_ID = "akka-contrib-mongodb-persistence-connection-journal";
    private static final String SNAPSHOT_PLUGIN_ID = "akka-contrib-mongodb-persistence-connection-snapshots";

    private static final int SHUTDOWN_DELAY_SECONDS = 10;
    private static final FiniteDuration SHUTDOWN_DELAY = Duration.apply(SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS);
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private static final String PUB_SUB_GROUP_PREFIX = "connection:";

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private final String connectionId;
    private final ActorRef pubSubMediator;
    private final long snapshotThreshold;
    private final SnapshotAdapter<ConnectionData> snapshotAdapter;
    private final ConnectionActorPropsFactory propsFactory;
    private final Receive connectionCreatedBehaviour;
    private ConnectionStatus connectionStatus;

    @Nullable private ActorRef clientActor;
    @Nullable private Connection connection;
    @Nullable private List<MappingContext> mappingContexts;

    @Nullable private Cancellable shutdownCancellable;

    private long lastSnapshotSequenceNr = -1L;
    private boolean snapshotInProgress = false;

    @Nullable private String connectionStatusDetails;

    private ConnectionActor(final String connectionId, final ActorRef pubSubMediator,
            final ConnectionActorPropsFactory propsFactory) {
        this.connectionId = connectionId;
        this.pubSubMediator = pubSubMediator;
        this.propsFactory = propsFactory;

        final Config config = getContext().system().settings().config();
        snapshotThreshold = config.getLong(ConfigKeys.Connection.SNAPSHOT_THRESHOLD);
        if (snapshotThreshold < 0) {
            throw new ConfigurationException(String.format("Config setting '%s' must be positive, but is: %d.",
                    ConfigKeys.Connection.SNAPSHOT_THRESHOLD, snapshotThreshold));
        }
        snapshotAdapter = new ConnectionMongoSnapshotAdapter();

        connectionStatus = ConnectionStatus.CLOSED;
        mappingContexts = Collections.emptyList();

        connectionCreatedBehaviour = createConnectionCreatedBehaviour();
    }


    /**
     * Creates Akka configuration object for this actor.
     *
     * @return the Akka configuration Props object
     */
    public static Props props(final String connectionId, final ActorRef pubSubMediator,
            final ConnectionActorPropsFactory propsFactory) {
        return Props.create(ConnectionActor.class, new Creator<ConnectionActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public ConnectionActor create() {
                return new ConnectionActor(connectionId, pubSubMediator, propsFactory);
            }
        });
    }

    @Override
    public String persistenceId() {
        return PERSISTENCE_ID_PREFIX + connectionId;
    }

    @Override
    public String journalPluginId() {
        return JOURNAL_PLUGIN_ID;
    }

    @Override
    public String snapshotPluginId() {
        return SNAPSHOT_PLUGIN_ID;
    }

    @Override
    public void postStop() {
        super.postStop();
        if (shutdownCancellable != null) {
            shutdownCancellable.cancel();
        }
    }

    @Override
    public Receive createReceiveRecover() {
        return ReceiveBuilder.create()
                // # Snapshot handling
                .match(SnapshotOffer.class, ss -> {
                    final ConnectionData fromSnapshotStore = snapshotAdapter.fromSnapshotStore(ss);
                    log.info("Received SnapshotOffer containing connectionStatus: <{}>", fromSnapshotStore);
                    if (fromSnapshotStore != null) {
                        connection = fromSnapshotStore.getConnection();
                        mappingContexts = fromSnapshotStore.getMappingContexts();
                        connectionStatus = fromSnapshotStore.getConnectionStatus();
                    }
                    lastSnapshotSequenceNr = ss.metadata().sequenceNr();
                })
                .match(ConnectionCreated.class, event -> {
                    connection = event.getConnection();
                    mappingContexts = event.getMappingContexts();
                    connectionStatus = ConnectionStatus.OPEN;
                })
                .match(ConnectionOpened.class, event -> connectionStatus = ConnectionStatus.OPEN)
                .match(ConnectionClosed.class, event -> connectionStatus = ConnectionStatus.CLOSED)
                .match(ConnectionDeleted.class, event -> {
                    connection = null;
                    mappingContexts = Collections.emptyList();
                    connectionStatus = ConnectionStatus.CLOSED;
                })
                .match(RecoveryCompleted.class, rc -> {
                    log.info("Connection '{}' was recovered: {}", connectionId, connection);
                    if (connection != null) {
                        connectionStatusDetails = null;
                        if (ConnectionStatus.OPEN.equals(connectionStatus)) {
                            log.debug("Opening connection {} after recovery.", connectionId);

                            final CreateConnection connect;
                            if (mappingContexts != null) {
                                connect = CreateConnection.of(connection, mappingContexts, DittoHeaders.empty());
                            } else {
                                connect = CreateConnection.of(connection, DittoHeaders.empty());
                            }

                            final ActorRef origin = getSender();
                            askClientActor("recovery-connect", connect, origin,
                                    response -> log.info("CreateConnection result: {}", response));
                            subscribeToThingEvents();
                        }
                        getContext().become(connectionCreatedBehaviour);
                    }

                    scheduleShutdown();
                    getContext().getParent().tell(ConnectionSupervisorActor.ManualReset.getInstance(), getSelf());
                })
                .matchAny(m -> log.warning("Unknown recover message: {}", m))
                .build();
    }

    @Override
    public Receive createReceive() {
        return ReceiveBuilder.create()
                .match(CreateConnection.class, this::createConnection)
                .match(ConnectivityCommand.class, this::handleCommandDuringInitialization)
                .match(Shutdown.class, shutdown -> stopSelf())
                .match(Status.Failure.class, f -> {
                    log.error(f.cause(), "Got failure: {}", f);
                    connectionStatusDetails = f.cause().getMessage();
                })
                .matchAny(m -> {
                    log.warning("Unknown message: {}", m);
                    unhandled(m);
                }).build();
    }

    private Receive createConnectionCreatedBehaviour() {
        return ReceiveBuilder.create()
                .match(CreateConnection.class, createConnection -> {
                    LogUtil.enhanceLogWithCorrelationId(log, createConnection);
                    log.info("Connection <{}> already exists, responding with conflict", createConnection.getId());
                    final ConnectionConflictException conflictException =
                            ConnectionConflictException.newBuilder(createConnection.getId())
                                    .dittoHeaders(createConnection.getDittoHeaders())
                                    .build();
                    getSender().tell(conflictException, getSelf());
                })
                .match(OpenConnection.class, this::openConnection)
                .match(CloseConnection.class, this::closeConnection)
                .match(DeleteConnection.class, this::deleteConnection)
                .match(RetrieveConnection.class, this::retrieveConnection)
                .match(RetrieveConnectionStatus.class, this::retrieveConnectionStatus)
                .match(ThingEvent.class, this::handleThingEvent)
                .match(DistributedPubSubMediator.SubscribeAck.class, this::handleSubscribeAck)
                .match(DistributedPubSubMediator.UnsubscribeAck.class, this::handleUnsubscribeAck)
                .match(SaveSnapshotSuccess.class, this::handleSnapshotSuccess)
                .match(Shutdown.class, shutdown -> log.debug("Dropping Shutdown in created behaviour state."))
                .match(Status.Failure.class, f -> log.error(f.cause(), "Got failure: {}", f))
                .matchAny(m -> {
                    log.warning("Unknown message: {}", m);
                    unhandled(m);
                }).build();
    }

    private void handleThingEvent(final ThingEvent<?> thingEvent) {
        if (clientActor == null) {
            log.debug("Cannot forward thing event, client actor not ready.");
            return;
        }
        if (connection == null) {
            log.debug("No Connection configuration available.");
            return;
        }
        if (!connection.getEventTarget().isPresent()) {
            log.debug("No event target configured, dropping all events.");
            return;
        }

        final Set<String> authorizedReadSubjects = thingEvent.getDittoHeaders().getReadSubjects();
        // forward to client actor router if the configured subject is allowed to read
        final List<String> connectionSubjects =
                connection.getAuthorizationContext().getAuthorizationSubjectIds();
        if (!Collections.disjoint(authorizedReadSubjects, connectionSubjects)) {
            log.debug("Forwarding thing event <{}> to client actor.", thingEvent.getType());
            clientActor.tell(thingEvent, getSelf());
        }
    }

    private void createConnection(final CreateConnection command) {

        final ConnectionCreated connectionCreated =
                ConnectionCreated.of(command.getConnection(), command.getMappingContexts(),
                        command.getDittoHeaders());
        final ActorRef origin = getSender();

        persistEvent(connectionCreated, persistedEvent -> {
            connection = persistedEvent.getConnection();
            mappingContexts = persistedEvent.getMappingContexts();
            connectionStatus = ConnectionStatus.OPEN;

            askClientActor("connect", command, origin, response -> {
                getContext().become(connectionCreatedBehaviour);
                subscribeToThingEvents();
                origin.tell(
                        CreateConnectionResponse.of(connection, mappingContexts, command.getDittoHeaders()),
                        getSelf());
                getContext().getParent().tell(ConnectionSupervisorActor.ManualReset.getInstance(), getSelf());
            });
        });
    }

    private void openConnection(final OpenConnection command) {
        checkNotNull(connection, "Connection");

        final ConnectionOpened connectionOpened =
                ConnectionOpened.of(command.getConnectionId(), command.getDittoHeaders());
        final ActorRef origin = getSender();

        persistEvent(connectionOpened, persistedEvent -> {
            connectionStatus = ConnectionStatus.OPEN;
            askClientActor("open-connection", command, origin, response -> {
                subscribeToThingEvents();
                origin.tell(OpenConnectionResponse.of(connectionId, command.getDittoHeaders()), getSelf());
            });
        });
    }

    private void closeConnection(final CloseConnection command) {

        final ConnectionClosed connectionClosed =
                ConnectionClosed.of(command.getConnectionId(), command.getDittoHeaders());
        final ActorRef origin = getSender();

        persistEvent(connectionClosed, persistedEvent -> {
            connectionStatus = ConnectionStatus.CLOSED;
            askClientActor("disconnect", command, origin, response -> {
                origin.tell(CloseConnectionResponse.of(connectionId, command.getDittoHeaders()),
                        getSelf());
                unsubscribeFromThingEvents();
                connectionStatusDetails = "closed as requested by CloseConnection command";
            });
        });
    }

    private void deleteConnection(final DeleteConnection command) {

        final ConnectionDeleted connectionDeleted =
                ConnectionDeleted.of(command.getConnectionId(), command.getDittoHeaders());
        final ActorRef origin = getSender();

        persistEvent(connectionDeleted,
                persistedEvent -> {
                    connectionStatus = ConnectionStatus.CLOSED;
                    askClientActor("disconnect", command, origin, response -> {
                        unsubscribeFromThingEvents();
                        stopClientActor();
                        origin.tell(DeleteConnectionResponse.of(connectionId, command.getDittoHeaders()),
                                getSelf());
                        stopSelf();
                    });
                });
    }

    private void askClientActor(final String action, final Command<?> cmd, final ActorRef origin,
            final Consumer<Object> onSuccess) {

        startClientActorIfRequired();
        long timeout = Optional.ofNullable(cmd.getDittoHeaders().get("timeout"))
                .map(Long::parseLong)
                .orElse(DEFAULT_TIMEOUT_MS);
        // wrap in Broadcast message because these management messages must be delivered to each client actor
        final Broadcast broadcastCommand = new Broadcast(cmd);
        PatternsCS.ask(clientActor, broadcastCommand, timeout)
                .whenComplete((response, exception) -> {
                    log.debug("Got response to {}: {}", cmd.getType(), exception == null ? response : exception);
                    if (exception != null) {
                        handleException(action, origin, exception);
                    } else if (response instanceof Status.Failure) {
                        handleException(action, origin, ((Status.Failure) response).cause());
                    } else {
                        onSuccess.accept(response);
                    }
                });
    }

    private void handleException(final String action, final ActorRef origin, final Throwable exception) {
        final DittoRuntimeException dre;
        if (exception instanceof DittoRuntimeException) {
            dre = (DittoRuntimeException) exception;
        } else {
            dre = ConnectionFailedException.newBuilder(connectionId)
                    .description(exception.getMessage())
                    .build();
        }

        connectionStatus = ConnectionStatus.FAILED;
        connectionStatusDetails = dre.getMessage() + " " + dre.getDescription().orElse("");

        origin.tell(dre, getSelf());
        log.error(dre, "Operation '{}' on connection '{}' failed: {}.", action,
                connectionId,
                exception.getMessage());
    }

    private void retrieveConnection(final RetrieveConnection command) {
        checkNotNull(connection, "Connection");
        checkNotNull(mappingContexts, "MappingContexts");
        getSender().tell(RetrieveConnectionResponse.of(connection, mappingContexts, command.getDittoHeaders()),
                getSelf());
    }

    private void retrieveConnectionStatus(final RetrieveConnectionStatus command) {
        checkNotNull(connection, "Connection");
        checkNotNull(mappingContexts, "MappingContexts");
        getSender().tell(RetrieveConnectionStatusResponse.of(connectionId, connectionStatus, connectionStatusDetails,
                command.getDittoHeaders()), getSelf());
    }

    private void subscribeToThingEvents() {
        checkNotNull(connection, "connection");
        if (connection.getEventTarget().isPresent()) {
            final DistributedPubSubMediator.Subscribe subscribe =
                    new DistributedPubSubMediator.Subscribe(ThingEvent.TYPE_PREFIX, PUB_SUB_GROUP_PREFIX + connectionId,
                            getSelf());
            pubSubMediator.tell(subscribe, getSelf());
        }
    }

    private void unsubscribeFromThingEvents() {
        pubSubMediator.tell(
                new DistributedPubSubMediator.Unsubscribe(ThingEvent.TYPE_PREFIX, PUB_SUB_GROUP_PREFIX + connectionId,
                        getSelf()), getSelf());
    }

    private void handleCommandDuringInitialization(final ConnectivityCommand command) {
        log.debug("Unexpected command during initialization of actor received: {} - "
                        + "Terminating this actor and sending 'ConnectionNotAccessibleException' to requester..",
                command.getType());
        getSender().tell(ConnectionNotAccessibleException.newBuilder(command.getId())
                .dittoHeaders(command.getDittoHeaders())
                .build(), getSelf());
    }

    private <E extends Event> void persistEvent(final E event, final Consumer<E> consumer) {
        persist(event, persistedEvent -> {
            log.debug("Successfully persisted Event '{}'", persistedEvent.getType());
            consumer.accept(persistedEvent);
            pubSubMediator.tell(new DistributedPubSubMediator.Publish(event.getType(), event, true), getSelf());

            // save a snapshot if there were too many changes since the last snapshot
            if ((lastSequenceNr() - lastSnapshotSequenceNr) > snapshotThreshold) {
                doSaveSnapshot();
            }
        });
    }

    private void doSaveSnapshot() {
        if (snapshotInProgress) {
            log.debug("Already requested taking a Snapshot - not doing it again");
        } else if (connection != null && mappingContexts != null) {
            snapshotInProgress = true;
            final ConnectionData connectionData = new ConnectionData(connection, connectionStatus, mappingContexts);
            log.info("Attempting to save Snapshot for '{}' ..", connectionData);
            // save a snapshot
            final Object snapshotToStore = snapshotAdapter.toSnapshotStore(connectionData);
            saveSnapshot(snapshotToStore);
        } else {
            log.warning("Connection and MappingContext must not be null when taking snapshot.");
        }
    }

    private void startClientActorIfRequired() {
        checkNotNull(connectionId, "connectionId");
        checkNotNull(connection, "connection");
        if (clientActor == null) {
            final int consumerCount = connection.getConsumerCount();
            log.info("Starting ClientActor for connection <{}> with {} consumers.", connectionId, consumerCount);
            final Props props = propsFactory.getActorPropsForType(getSelf(), connection);
            final ClusterRouterPoolSettings clusterRouterPoolSettings =
                    new ClusterRouterPoolSettings(consumerCount, 1, true,
                            Collections.singleton(CLUSTER_ROLE));
            final RoundRobinPool roundRobinPool = new RoundRobinPool(consumerCount);
            final Props clusterRouterPoolProps =
                    new ClusterRouterPool(roundRobinPool, clusterRouterPoolSettings).props(props);
            clientActor = getContext().actorOf(clusterRouterPoolProps, "rtr-" + connectionId);
        } else {
            log.debug("ClientActor already started.");
        }
    }

    private void stopClientActor() {
        if (clientActor != null) {
            log.debug("Stopping the client actor.");
            stopChildActor(clientActor);
            clientActor = null;
        }
    }

    private void stopChildActor(final ActorRef actor) {
        log.debug("Stopping child actor '{}'", actor.path());
        getContext().stop(actor);
    }

    private void stopSelf() {
        log.debug("Shutting down");
        // stop the supervisor (otherwise it'd restart this actor) which causes this actor to stop, too.
        getContext().getParent().tell(PoisonPill.getInstance(), getSelf());
    }

    private void scheduleShutdown() {
        shutdownCancellable = getContext().getSystem().scheduler()
                .scheduleOnce(SHUTDOWN_DELAY,
                        getSelf(),
                        Shutdown.getInstance(),
                        getContext().dispatcher(),
                        ActorRef.noSender());
    }

    private void handleSubscribeAck(final DistributedPubSubMediator.SubscribeAck subscribeAck) {
        log.debug("Successfully subscribed to distributed pub/sub on topic '{}'", subscribeAck.subscribe().topic());
    }

    private void handleUnsubscribeAck(final DistributedPubSubMediator.UnsubscribeAck unsubscribeAck) {
        log.debug("Successfully unsubscribed from distributed pub/sub on topic '{}'",
                unsubscribeAck.unsubscribe().topic());
    }

    private void handleSnapshotSuccess(final SaveSnapshotSuccess sss) {
        log.debug("Snapshot was saved successfully: {}", sss);
    }

    private static class Shutdown {

        private Shutdown() {
            // no-op
        }

        private static Shutdown getInstance() {
            return new Shutdown();
        }

    }

}
