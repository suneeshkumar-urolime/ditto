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
package org.eclipse.ditto.services.concierge.starter.proxy;

import static org.eclipse.ditto.services.models.concierge.ConciergeMessagingConstants.CLUSTER_ROLE;
import static org.eclipse.ditto.services.models.concierge.ConciergeMessagingConstants.SHARD_REGION;

import java.util.Optional;

import org.eclipse.ditto.services.base.config.ClusterConfigReader;
import org.eclipse.ditto.services.concierge.util.config.ConciergeConfigReader;
import org.eclipse.ditto.services.utils.cluster.ShardRegionExtractor;

import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.cluster.sharding.ClusterSharding;
import akka.cluster.sharding.ClusterShardingSettings;

/**
 * Defines the {@link Props} which are used to create an Authorization Proxy Actor for an entity.
 */
public abstract class AuthorizationProxyPropsFactory {

    /**
     * Start a proxy to a shard region.
     *
     * @param actorSystem actor system to start the proxy in.
     * @param numberOfShards number of shards in the shard region.
     * @param shardRegionName name of the shard region.
     * @param clusterRole role of the shard region.
     * @return actor reference to the shard region proxy.
     */
    protected static ActorRef startProxy(final ActorSystem actorSystem,
            final int numberOfShards,
            final String shardRegionName,
            final String clusterRole) {

        final ShardRegionExtractor shardRegionExtractor = ShardRegionExtractor.of(numberOfShards, actorSystem);

        return ClusterSharding.get(actorSystem)
                .startProxy(shardRegionName, Optional.of(clusterRole), shardRegionExtractor);
    }

    /**
     * Start a shard region.
     *
     * @param actorSystem actor system to start the proxy in.
     * @param clusterConfigReader the cluster configuration
     * @param props props of actors to start in the shard.
     * @return actor reference to the shard region.
     */
    protected static ActorRef startShardRegion(final ActorSystem actorSystem,
            final ClusterConfigReader clusterConfigReader,
            final Props props) {

        final ClusterShardingSettings settings = ClusterShardingSettings.create(actorSystem)
                .withRole(CLUSTER_ROLE);

        final ShardRegionExtractor extractor =
                ShardRegionExtractor.of(clusterConfigReader.numberOfShards(), actorSystem);

        return ClusterSharding.get(actorSystem).start(SHARD_REGION, props, settings, extractor);
    }

    /**
     * Start a child actor.
     *
     * @param context actor context to start the child actor in.
     * @param actorName the name of the actor.
     * @param props props of actors to start.
     * @return actor reference to the child actor.
     */
    protected static ActorRef startChildActor(final ActorContext context, final String actorName, final Props props) {
        return context.actorOf(props, actorName);
    }

    /**
     * Start actors of Concierge service.
     *
     * @param context context in which to start actors other than shard regions and shard region proxies.
     * @param configReader the configuration reader of Concierge service.
     * @param pubSubMediator Akka pub-sub mediator.
     * @return actor reference to Concierge shard region.
     */
    public abstract ActorRef startActors(final ActorContext context,
            final ConciergeConfigReader configReader,
            final ActorRef pubSubMediator);

    /**
     * Start the health checking actor for the Concierge service.
     *
     * @param context context in which to start actors other than shard regions and shard region proxies.
     * @param configReader the configuration reader of Concierge service.
     * @return actor reference to Concierge shard region.
     */
    public abstract ActorRef startHealthCheckingActor(final ActorContext context,
            final ConciergeConfigReader configReader);

}
