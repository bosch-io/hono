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

package org.eclipse.hono.application.client.kafka;

import org.eclipse.hono.application.client.ApplicationClientFactory;
import org.eclipse.hono.application.client.DownstreamMessage;
import org.eclipse.hono.application.client.MessageConsumer;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.client.kafka.consumer.KafkaConsumerCommitException;
import org.eclipse.hono.client.kafka.consumer.KafkaConsumerPollException;

import io.vertx.core.Future;
import io.vertx.core.Handler;

/**
 * A factory for creating clients for Hono's Kafka-based northbound APIs.
 */
public interface KafkaApplicationClientFactory extends ApplicationClientFactory<KafkaMessageContext> {

    /**
     * Creates a client for consuming data from Hono's north bound <em>Telemetry API</em>.
     * <p>
     * The messages passed in to the consumer will be acknowledged automatically when the message handler completes. <b>
     * The message handler is expected to handle processing errors internally and should not deliberately throw
     * exceptions.</b> Any exception in the message handler will stop the consumption permanently, because a new
     * consumer will try to consume the same message again and will then get the same exception.
     * <p>
     * If a fatal error occurs, the consumer will be closed and the close-handler, if it is not {@code null}, invoked
     * with an exception indicating the cause. There are error cases that might disappear later on and where it makes
     * sense to create a new consumer and other cases that need to be resolved externally.
     * <p>
     * </p>
     * ERROR CASES:
     * <p>
     * Errors can happen when polling, in message processing, and when committing the offset to Kafka. If a {@code poll}
     * operation fails, the consumer will be closed and the close handler will be passed a
     * {@link KafkaConsumerPollException} indicating the cause. The provided message handler may throw
     * a{@link ServerErrorException} to indicate a transient error. If the message handler throws another exception, the
     * consumer will be closed and the exception will be passed to the close handler. If the offset commit fails, the
     * consumer will be closed and the close handler will be passed a {@link KafkaConsumerCommitException}.
     *
     * @param tenantId The tenant to consume data for.
     * @param messageHandler The handler to be invoked for each message created from a record. The handler may throw a
     *            {@link ServerErrorException} to indicate a transient error but should not throw any other exceptions.
     * @param closeHandler The handler invoked when the consumer is closed due to an error.
     * @return A future that will complete with the consumer once it is ready. The future will fail if the consumer
     *         cannot be started.
     * @throws NullPointerException if any of tenant ID or message handler are {@code null}.
     */
    @Override
    Future<MessageConsumer> createTelemetryConsumer(
            String tenantId,
            Handler<DownstreamMessage<KafkaMessageContext>> messageHandler,
            Handler<Throwable> closeHandler);

    /**
     * Creates a client for consuming data from Hono's north bound <em>Event API</em>.
     * <p>
     * The messages passed in to the consumer will be acknowledged automatically when the message handler completes. <b>
     * The message handler is expected to handle processing errors internally and should not deliberately throw
     * exceptions.</b> Any exception in the message handler will stop the consumption permanently, because a new
     * consumer will try to consume the same message again and will then get the same exception.
     * <p>
     * If a fatal error occurs, the consumer will be closed and the close-handler, if it is not {@code null}, invoked
     * with an exception indicating the cause. There are error cases that might disappear later on and where it makes
     * sense to create a new consumer and other cases that need to be resolved externally.
     * <p>
     * </p>
     * ERROR CASES:
     * <p>
     * Errors can happen when polling, in message processing, and when committing the offset to Kafka. If a {@code poll}
     * operation fails, the consumer will be closed and the close handler will be passed a
     * {@link KafkaConsumerPollException} indicating the cause. If the provided message handler throws an exception, the
     * consumer will be closed and the exception will be passed to the close handler. If the offset commit fails, the
     * consumer will be closed and the close handler will be passed a {@link KafkaConsumerCommitException}.
     *
     * @param tenantId The tenant to consume data for.
     * @param messageHandler The handler to be invoked for each message created from a record. The handler may throw a
     *            {@link ServerErrorException} to indicate a transient error but should not throw any other exceptions.
     * @param closeHandler The handler invoked when the consumer is closed due to an error.
     * @return A future that will complete with the consumer once it is ready. The future will fail if the consumer
     *         cannot be started.
     * @throws NullPointerException if any of tenant ID or message handler are {@code null}.
     */
    @Override
    Future<MessageConsumer> createEventConsumer(
            String tenantId,
            Handler<DownstreamMessage<KafkaMessageContext>> messageHandler,
            Handler<Throwable> closeHandler);
}
