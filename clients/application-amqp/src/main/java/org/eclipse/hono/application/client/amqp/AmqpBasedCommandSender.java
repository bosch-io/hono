/*******************************************************************************
 * Copyright (c) 2016, 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.hono.application.client.amqp;

import java.util.Map;

import org.eclipse.hono.application.client.CommandSender;
import org.eclipse.hono.client.ServiceInvocationException;

import io.opentracing.SpanContext;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/**
 * An AMQP 1.0 based client that supports Hono's north bound operations to send commands.
 *
 * @see <a href="https://www.eclipse.org/hono/docs/api/command-and-control/">
 *      Command &amp; Control API for AMQP 1.0 Specification</a>
 */
public interface AmqpBasedCommandSender extends CommandSender {

    /**
     * Sends a <em>one-way command</em> to a device, i.e. there is no response from the device expected.
     * <p>
     * A device needs to be (successfully) registered before a client can upload
     * any data for it. The device also needs to be connected to a protocol adapter
     * and needs to have indicated its intent to receive commands.
     *
     * @param tenantId The tenant that the device belongs to.
     * @param deviceId The device to send the command to.
     * @param command The name of the command.
     * @param contentType The type of the data submitted as part of the one-way command or {@code null} if unknown.
     * @param data The input data to the command or {@code null} if the command has no input data.
     * @param properties The headers to include in the one-way command message.
     * @param sendMessageTimeout The maximum number of milliseconds to wait.
     * @param context The currently active OpenTracing span context that is used to trace the execution of this
     *            operation or {@code null} if no span is currently active.
     * @return A future indicating the result of the operation:
     *         <p>
     *         If the one-way command was accepted, the future will succeed.
     *         <p>
     *         The future will fail with a {@link ServiceInvocationException} if the one-way command could 
     *         not be forwarded to the device.
     * @throws NullPointerException if any of tenantId, deviceId or command are {@code null}.
     * @throws IllegalArgumentException if the sendMessageTimeout value is &lt; 0
     */
    Future<Void> sendOneWayCommand(
            String tenantId,
            String deviceId,
            String command,
            String contentType,
            Buffer data,
            Map<String, Object> properties,
            long sendMessageTimeout,
            SpanContext context);
}
