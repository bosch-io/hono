/**
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


package org.eclipse.hono.application.client.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Modified;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.messaging.Released;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.application.client.DownstreamMessage;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.amqp.test.AmqpClientUnitTestHelper;
import org.eclipse.hono.test.VertxMockSupport;
import org.eclipse.hono.util.MessageHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import io.opentracing.noop.NoopSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonMessageHandler;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;


/**
 * Tests verifying behavior of {@link ProtonBasedApplicationClient}.
 *
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
class ProtonBasedApplicationClientTest {

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<Handler<ProtonDelivery>> dispositionHandlerCaptor = ArgumentCaptor
            .forClass(Handler.class);
    private final ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    private HonoConnection connection;
    private ProtonBasedApplicationClient client;
    private ProtonSender sender;
    private ProtonDelivery protonDelivery;

    /**
     * Sets up the fixture.
     */
    @BeforeEach
    void setUp() {
        final var vertx = mock(Vertx.class);
        when(vertx.eventBus()).thenReturn(mock(EventBus.class));
        connection = AmqpClientUnitTestHelper.mockHonoConnection(vertx);
        final ProtonReceiver receiver = AmqpClientUnitTestHelper.mockProtonReceiver();
        when(connection.createReceiver(
                anyString(),
                any(ProtonQoS.class),
                any(ProtonMessageHandler.class),
                anyInt(),
                anyBoolean(),
                VertxMockSupport.anyHandler())).thenReturn(Future.succeededFuture(receiver));

        protonDelivery = mock(ProtonDelivery.class);
        when(protonDelivery.remotelySettled()).thenReturn(true);
        when(protonDelivery.getRemoteState()).thenReturn(new Accepted());
        sender = AmqpClientUnitTestHelper.mockProtonSender();
        when(sender.send(any(Message.class), VertxMockSupport.anyHandler())).thenReturn(protonDelivery);
        when(connection.createSender(anyString(), any(), any())).thenReturn(Future.succeededFuture(sender));

        client = new ProtonBasedApplicationClient(connection);
    }

    /**
     * Verifies that starting the client triggers the underlying connection to be established.
     *
     * @param ctx The vertx test context.
     */
    @Test
    void testConnectTriggersConnectionEstablishment(final VertxTestContext ctx) {
        when(connection.connect()).thenReturn(Future.succeededFuture(connection));
        client.connect().onComplete(ctx.succeeding(ok -> {
            ctx.verify(() -> verify(connection).connect());
            ctx.completeNow();
        }));
    }

    /**
     * Verifies that disconnecting the client stops the underlying connection.
     */
    @Test
    void testDisconnectTerminatesConnection() {
        client.disconnect();
        verify(connection).disconnect(VertxMockSupport.anyHandler());
    }

    /**
     * Verifies that disconnecting the client stops the underlying connection.
     */
    @Test
    void testDisconnectWithHandlerTerminatesConnection() {

        final Promise<Void> result = Promise.promise();
        client.disconnect(result);
        assertThat(result.future().isComplete()).isFalse();
        final ArgumentCaptor<Handler<AsyncResult<Void>>> resultHandler = VertxMockSupport.argumentCaptorHandler();
        verify(connection).disconnect(resultHandler.capture());
        resultHandler.getValue().handle(Future.succeededFuture());
        assertThat(result.future().succeeded()).isTrue();
    }

    /**
     * Verifies that the message consumer created by the factory catches an exception
     * thrown by the client provided handler and releases the message.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testCreateTelemetryConsumerReleasesMessageOnException(final VertxTestContext ctx) {

        // GIVEN a client provided message handler that throws an exception on
        // each message received
        final Handler<DownstreamMessage<AmqpMessageContext>> consumer = VertxMockSupport.mockHandler();
        doThrow(new IllegalArgumentException("message does not contain required properties"),
                new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST))
            .when(consumer).handle(any(DownstreamMessage.class));

        client.createTelemetryConsumer("tenant", consumer, t -> {})
            .onComplete(ctx.succeeding(mc -> {
                final ArgumentCaptor<ProtonMessageHandler> messageHandler = ArgumentCaptor.forClass(ProtonMessageHandler.class);
                ctx.verify(() -> {
                    verify(connection).createReceiver(
                            eq("telemetry/tenant"),
                            eq(ProtonQoS.AT_LEAST_ONCE),
                            messageHandler.capture(),
                            anyInt(),
                            anyBoolean(),
                            VertxMockSupport.anyHandler());

                    final var msg = ProtonHelper.message();
                    // WHEN a message is received and the client provided consumer
                    // throws an IllegalArgumentException
                    var delivery = mock(ProtonDelivery.class);
                    messageHandler.getValue().handle(delivery, msg);
                    // THEN the message is forwarded to the client provided handler
                    verify(consumer).handle(any(DownstreamMessage.class));
                    // AND the AMQP message is being released
                    verify(delivery).disposition(any(Released.class), eq(Boolean.TRUE));

                    // WHEN a message is received and the client provided consumer
                    // throws a ClientErrorException
                    delivery = mock(ProtonDelivery.class);
                    messageHandler.getValue().handle(delivery, msg);
                    // THEN the message is forwarded to the client provided handler
                    verify(consumer, times(2)).handle(any(DownstreamMessage.class));
                    // AND the AMQP message is being rejected
                    verify(delivery).disposition(any(Rejected.class), eq(Boolean.TRUE));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the message consumer created by the factory allows the client provided handler
     * to manually perform a disposition update for a received message.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testCreateTelemetryConsumerSupportsManualDispositionHandling(final VertxTestContext ctx) {

        // GIVEN a client provided message handler that manually settles messages
        final Handler<DownstreamMessage<AmqpMessageContext>> consumer = VertxMockSupport.mockHandler();

        // WHEN creating a telemetry consumer
        client.createTelemetryConsumer("tenant", consumer, t -> {})
            .onComplete(ctx.succeeding(mc -> {
                final ArgumentCaptor<ProtonMessageHandler> messageHandler = ArgumentCaptor.forClass(ProtonMessageHandler.class);
                ctx.verify(() -> {
                    verify(connection).createReceiver(
                            eq("telemetry/tenant"),
                            eq(ProtonQoS.AT_LEAST_ONCE),
                            messageHandler.capture(),
                            anyInt(),
                            anyBoolean(),
                            VertxMockSupport.anyHandler());
                    final var delivery = mock(ProtonDelivery.class);
                    final var msg = ProtonHelper.message();
                    // over which a message is being received
                    messageHandler.getValue().handle(delivery, msg);
                    // THEN the message is forwarded to the client provided handler
                    final ArgumentCaptor<DownstreamMessage<AmqpMessageContext>> downstreamMessage = ArgumentCaptor.forClass(DownstreamMessage.class);
                    verify(consumer).handle(downstreamMessage.capture());
                    final var messageContext = downstreamMessage.getValue().getMessageContext();
                    ProtonHelper.modified(messageContext.getDelivery(), true, true, true);
                    // AND the AMQP message is being settled with the modified outcome
                    verify(delivery).disposition(any(Modified.class), eq(Boolean.TRUE));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the message consumer created by the factory settles an event with the
     * accepted outcome if the client provided handler does not throw an exception.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testCreateEventConsumerAcceptsMessage(final VertxTestContext ctx) {

        // GIVEN a client provided message handler
        final Handler<DownstreamMessage<AmqpMessageContext>> consumer = VertxMockSupport.mockHandler();

        // WHEN creating an event consumer
        client.createEventConsumer("tenant", consumer, t -> {})
            .onComplete(ctx.succeeding(mc -> {
                final ArgumentCaptor<ProtonMessageHandler> messageHandler = ArgumentCaptor.forClass(ProtonMessageHandler.class);
                ctx.verify(() -> {
                    verify(connection).createReceiver(
                            eq("event/tenant"),
                            eq(ProtonQoS.AT_LEAST_ONCE),
                            messageHandler.capture(),
                            anyInt(),
                            anyBoolean(),
                            VertxMockSupport.anyHandler());
                    final var delivery = mock(ProtonDelivery.class);
                    when(delivery.isSettled()).thenReturn(Boolean.FALSE);
                    final var msg = ProtonHelper.message();
                    // over which a message is being received
                    messageHandler.getValue().handle(delivery, msg);
                    // THEN the message is forwarded to the client provided handler
                    verify(consumer).handle(any(DownstreamMessage.class));
                    // AND the AMQP message is being accepted
                    verify(delivery).disposition(any(Accepted.class), eq(Boolean.TRUE));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that a one-way command sent to a device succeeds.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testSendOneWayCommandSucceeds(final VertxTestContext ctx) {
        final String tenantId = UUID.randomUUID().toString();
        final String deviceId = UUID.randomUUID().toString();
        final String subject = "setVolume";
        final Map<String, Object> applicationProperties = Map.of("appKey", "appValue");

        // WHEN sending a one-way command with some application properties and payload
        final Future<Void> sendCommandFuture = client
                .sendOneWayCommand(tenantId, deviceId, subject, null, Buffer.buffer("{\"value\": 20}"),
                        applicationProperties, NoopSpan.INSTANCE.context())
                .onComplete(ctx.completing());

        // VERIFY that the command is being sent
        verify(sender).send(messageCaptor.capture(), dispositionHandlerCaptor.capture());

        // VERIFY that the future waits for the disposition to be updated by the peer
        assertThat(sendCommandFuture.isComplete()).isFalse();

        // THEN the disposition is updated and the peer accepts the message
        dispositionHandlerCaptor.getValue().handle(protonDelivery);

        //VERIFY if the message properties are properly set
        final Message message = messageCaptor.getValue();
        assertThat(MessageHelper.getDeviceId(message)).isEqualTo(deviceId);
        assertThat(message.getSubject()).isEqualTo(subject);
        assertThat(MessageHelper.getApplicationProperty(message.getApplicationProperties(), "appKey", String.class))
                .isEqualTo("appValue");
    }

    /**
     * Verifies that a command asynchronously sent to a device succeeds.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testSendAsyncCommandSucceeds(final VertxTestContext ctx) {
        final String tenantId = UUID.randomUUID().toString();
        final String deviceId = UUID.randomUUID().toString();
        final String subject = "setVolume";
        final String correlationId = UUID.randomUUID().toString();
        final String replyId = "reply-id";
        final Map<String, Object> applicationProperties = Map.of("appKey", "appValue");

        // WHEN sending a one-way command with some application properties and payload
        final Future<Void> sendCommandFuture = client
                .sendAsyncCommand(tenantId, deviceId, subject, null, Buffer.buffer("{\"value\": 20}"),
                        correlationId, replyId, applicationProperties, NoopSpan.INSTANCE.context())
                .onComplete(ctx.completing());

        // VERIFY that the command is being sent
        verify(sender).send(messageCaptor.capture(), dispositionHandlerCaptor.capture());

        // VERIFY that the future waits for the disposition to be updated by the peer
        assertThat(sendCommandFuture.isComplete()).isFalse();

        // THEN the disposition is updated and the peer accepts the message
        dispositionHandlerCaptor.getValue().handle(protonDelivery);

        //VERIFY if the message properties are properly set
        final Message message = messageCaptor.getValue();
        assertThat(MessageHelper.getDeviceId(message)).isEqualTo(deviceId);
        assertThat(message.getSubject()).isEqualTo(subject);
        assertThat(MessageHelper.getCorrelationId(message)).isEqualTo(correlationId);
        assertThat(MessageHelper.getApplicationProperty(message.getApplicationProperties(), "appKey", String.class))
                .isEqualTo("appValue");
    }

}
