/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 *
 */
package org.eclipse.ditto.services.things.persistence.actors.strategies.events;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.things.ThingBuilder;
import org.eclipse.ditto.signals.events.things.FeaturePropertiesDeleted;

/**
 * This strategy handles the {@link org.eclipse.ditto.signals.events.things.FeaturePropertiesDeleted} event.
 */
@Immutable
final class FeaturePropertiesDeletedStrategy extends AbstractEventStrategy<FeaturePropertiesDeleted> {

    @Override
    protected ThingBuilder.FromCopy applyEvent(final FeaturePropertiesDeleted event,
            final ThingBuilder.FromCopy thingBuilder) {

        return thingBuilder.removeFeatureProperties(event.getFeatureId());
    }

}
