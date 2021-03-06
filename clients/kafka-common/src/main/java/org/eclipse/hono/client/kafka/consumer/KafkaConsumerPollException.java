/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hono.client.kafka.consumer;

import java.time.Duration;

import org.apache.kafka.clients.consumer.KafkaConsumer;

/**
 * An exception indicating that a Kafka consumer failed to poll records.
 * <p>
 * Possible root causes for failing to poll are documented in {@link KafkaConsumer#poll(Duration)}.
 */
public class KafkaConsumerPollException extends KafkaConsumerException {

    private static final long serialVersionUID = -4108091688068963911L;

    /**
     * Creates a new exception for a root cause.
     *
     * @param cause The root cause.
     */
    public KafkaConsumerPollException(final Throwable cause) {
        super(cause);
    }

}
