/**
 * Copyright (c) 2020, 2021 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.deviceconnection.infinispan.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.test.TracingMockSupport;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.DeviceConnectionConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Tests verifying behavior of {@link CacheBasedDeviceConnectionInfo}.
 *
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
class CacheBasedDeviceConnectionInfoTest {

    private static final String PARAMETERIZED_TEST_NAME_PATTERN = "{displayName} [{index}]; parameters: {argumentsWithNames}";

    private DeviceConnectionInfo info;
    private Tracer tracer;
    private Span span;
    private Cache<String, String> cache;

    static Stream<Set<String>> extraUnusedViaGateways() {
        return Stream.of(
                Collections.emptySet(),
                getViaGatewaysExceedingThreshold()
        );
    }

    private static Set<String> getViaGatewaysExceedingThreshold() {
        final HashSet<String> set = new HashSet<>();
        for (int i = 0; i < CacheBasedDeviceConnectionInfo.VIA_GATEWAYS_OPTIMIZATION_THRESHOLD + 1; i++) {
            set.add("gw#" + i);
        }
        return set;
    }

    /**
     * Sets up the fixture.
     */
    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {

        span = TracingMockSupport.mockSpan();
        tracer = TracingMockSupport.mockTracer(span);
        cache = mock(Cache.class);
        info = new CacheBasedDeviceConnectionInfo(cache, tracer);
    }

    /**
     * Verifies that a last known gateway can be successfully set.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testSetLastKnownGatewaySucceeds(final VertxTestContext ctx) {

        when(cache.put(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(Future.succeededFuture("oldValue"));

        info.setLastKnownGatewayForDevice(Constants.DEFAULT_TENANT, "device-id", "gw-id", span)
            .onComplete(ctx.succeeding(ok -> {
                ctx.verify(() -> {
                    verify(cache).put(
                            eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, "device-id")),
                            eq("gw-id"),
                            anyLong(),
                            any(TimeUnit.class));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that a request to set a gateway fails with a {@link org.eclipse.hono.client.ServerErrorException}.
     * if the underlying cache is not available.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testSetLastKnownGatewayFails(final VertxTestContext ctx) {

        when(cache.put(anyString(), anyString(), anyLong(), any())).thenReturn(Future.failedFuture(new IOException("not available")));

        info.setLastKnownGatewayForDevice(Constants.DEFAULT_TENANT, "device-id", "gw-id", span)
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> {
                    verify(cache).put(
                            eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, "device-id")),
                            eq("gw-id"),
                            anyLong(),
                            any(TimeUnit.class));
                    assertThat(t).isInstanceOfSatisfying(
                            ServerErrorException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HttpURLConnection.HTTP_INTERNAL_ERROR));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that a last known gateway can be successfully retrieved.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testGetLastKnownGatewaySucceeds(final VertxTestContext ctx) {

        when(cache.get(anyString())).thenReturn(Future.succeededFuture("gw-id"));

        info.getLastKnownGatewayForDevice(Constants.DEFAULT_TENANT, "device-id", span)
            .onComplete(ctx.succeeding(value -> {
                ctx.verify(() -> {
                    verify(cache).get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, "device-id")));
                    assertThat(value.getString(DeviceConnectionConstants.FIELD_GATEWAY_ID)).isEqualTo("gw-id");
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that a request to get a gateway fails with a {@link org.eclipse.hono.client.ServiceInvocationException}
     * if the remote cache cannot be accessed.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testGetLastKnownGatewayFailsForCacheAccessException(final VertxTestContext ctx) {

        when(cache.get(anyString())).thenReturn(Future.failedFuture(new IOException("not available")));

        info.getLastKnownGatewayForDevice(Constants.DEFAULT_TENANT, "device-id", span)
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> {
                    verify(cache).get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, "device-id")));
                    assertThat(t).isInstanceOfSatisfying(
                            ServerErrorException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HttpURLConnection.HTTP_INTERNAL_ERROR));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that a request to get a gateway fails with a {@link org.eclipse.hono.client.ClientErrorException}
     * if no entry exists for the given device.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    void testGetLastKnownGatewayFailsForNonExistingEntry(final VertxTestContext ctx) {

        when(cache.get(anyString())).thenReturn(Future.succeededFuture(null));

        info.getLastKnownGatewayForDevice(Constants.DEFAULT_TENANT, "device-id", span)
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> {
                    verify(cache).get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, "device-id")));
                    assertThat(t).isInstanceOfSatisfying(
                            ClientErrorException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>setCommandHandlingAdapterInstance</em> operation succeeds.
     *
     * @param ctx The vert.x context.
     */
    @Test
    public void testSetCommandHandlingAdapterInstanceSucceeds(final VertxTestContext ctx) {

        when(cache.put(anyString(), anyString(), anyLong(), any())).thenReturn(Future.succeededFuture("oldValue"));

        info.setCommandHandlingAdapterInstance(Constants.DEFAULT_TENANT, "testDevice", "adapterInstance", null, span)
                .onComplete(ctx.succeeding(ok -> {
                    ctx.verify(() -> {
                        verify(cache).put(
                                eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, "testDevice")),
                                eq("adapterInstance"),
                                anyLong(),
                                any(TimeUnit.class));
                    });
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that the <em>setCommandHandlingAdapterInstance</em> operation succeeds
     * with a non-negative lifespan given.
     *
     * @param ctx The vert.x context.
     */
    @Test
    public void testSetCommandHandlingAdapterInstanceWithLifespanSucceeds(final VertxTestContext ctx) {

        when(cache.put(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(Future.succeededFuture("oldValue"));

        info.setCommandHandlingAdapterInstance(Constants.DEFAULT_TENANT, "testDevice", "adapterInstance", Duration.ofSeconds(10), span)
                .onComplete(ctx.succeeding(ok -> {
                    ctx.verify(() -> {
                        verify(cache).put(
                                eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, "testDevice")),
                                eq("adapterInstance"),
                                eq(10_000L),
                                eq(TimeUnit.MILLISECONDS));
                    });
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that the <em>removeCommandHandlingAdapterInstance</em> operation succeeds if there was an entry to be deleted.
     *
     * @param ctx The vert.x context.
     */
    @Test
    public void testRemoveCommandHandlingAdapterInstanceSucceeds(final VertxTestContext ctx) {

        when(cache.remove(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.TRUE));
        info.removeCommandHandlingAdapterInstance(Constants.DEFAULT_TENANT, "testDevice", "adapterInstance", span)
            .onComplete(ctx.succeeding(ok -> {
                ctx.verify(() -> verify(cache).remove(
                        eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, "testDevice")),
                        eq("adapterInstance")));
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>removeCommandHandlingAdapterInstance</em> operation fails with a PRECON_FAILED status if
     * no entry was registered for the device. Only an adapter instance for another device of the tenant was
     * registered.
     *
     * @param ctx The vert.x context.
     */
    @Test
    public void testRemoveCommandHandlingAdapterInstanceFailsForOtherDevice(final VertxTestContext ctx) {

        when(cache.remove(anyString(), anyString())).thenReturn(Future.succeededFuture(Boolean.FALSE));

        info.removeCommandHandlingAdapterInstance(Constants.DEFAULT_TENANT, "testDevice", "otherAdapter", span)
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> {
                    verify(cache).remove(
                            eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, "testDevice")),
                            eq("otherAdapter"));
                    assertThat(t).isInstanceOfSatisfying(
                            ClientErrorException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HttpURLConnection.HTTP_PRECON_FAILED));
                });
                ctx.completeNow();
             }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation succeeds if an adapter instance had
     * been registered for the given device.
     *
     * @param ctx The vert.x context.
     */
    @Test
    public void testGetCommandHandlingAdapterInstancesForDevice(final VertxTestContext ctx) {

        final var entry = new JsonObject()
                .put(DeviceConnectionConstants.FIELD_PAYLOAD_DEVICE_ID, "testDevice")
                .put(DeviceConnectionConstants.FIELD_ADAPTER_INSTANCE_ID, "adapterInstance");
        final var value = new JsonObject().put(DeviceConnectionConstants.FIELD_ADAPTER_INSTANCES, new JsonArray().add(entry));

        when(cache.get(anyString())).thenReturn(Future.succeededFuture("adapterInstance"));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, "testDevice", Set.of(), span)
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    verify(cache).get(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, "testDevice")));
                    assertThat(result).isEqualTo(value);
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation fails
     * if the adapter instance mapping entry has expired.
     *
     * @param vertx The vert.x instance.
     * @param ctx The vert.x context.
     */
    @Test
    public void testGetCommandHandlingAdapterInstancesWithExpiredEntry(final Vertx vertx, final VertxTestContext ctx) {

        when(cache.get(anyString())).thenReturn(Future.succeededFuture(null));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, "testDevice", Set.of(), span)
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> {
                    verify(cache).get(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, "testDevice")));
                    assertThat(t).isInstanceOfSatisfying(
                            ClientErrorException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation succeeds if an adapter instance has
     * been registered for the last known gateway associated with the given device.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesForLastKnownGateway(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String adapterInstance = "adapterInstance";
        final String otherAdapterInstance = "otherAdapterInstance";
        final String gatewayId = "gw-1";
        final String otherGatewayId = "gw-2";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId, otherGatewayId));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice's last known gateway is set to gw-1
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture(gatewayId));

        // and command handling adapter instances being set for both gateways
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of(
                CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), adapterInstance,
                CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, otherGatewayId), otherAdapterInstance)));

        // but no command handling adapter instance registered for testDevice
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, gatewayId))))
            .thenReturn(Future.succeededFuture(Map.of(
                CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), adapterInstance)));


        // WHEN retrieving the list of command handling adapter instances for the device
        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    // THEN the result contains the adapter instance registered for the gateway that the device
                    // has been last seen on
                    assertThat(result).isNotNull();
                    assertGetInstancesResultMapping(result, gatewayId, adapterInstance);
                    // be sure that only the mapping for the last-known-gateway is returned, not the mappings for both via gateways
                    assertGetInstancesResultSize(result, 1);
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation succeeds if multiple adapter instances
     * have been registered for gateways of the given device, but the last known gateway associated with the given
     * device is not in the viaGateways list of the device.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesWithMultiResultAndLastKnownGatewayNotInVia(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String adapterInstance = "adapterInstance";
        final String otherAdapterInstance = "otherAdapterInstance";
        final String gatewayId = "gw-1";
        final String otherGatewayId = "gw-2";
        final String gatewayIdNotInVia = "gw-old";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId, otherGatewayId));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice's last known gateway is set to gw-old
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture(gatewayIdNotInVia));

        // and command handling adapter instances being set for both gateways
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of(
                CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), adapterInstance,
                CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, otherGatewayId), otherAdapterInstance)));

        // but no command handling adapter instance registered for testDevice nor gw-old
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, gatewayIdNotInVia))))
            .thenReturn(Future.succeededFuture(Map.of()));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertThat(result).isNotNull();
                    assertGetInstancesResultMapping(result, gatewayId, adapterInstance);
                    assertGetInstancesResultMapping(result, otherGatewayId, otherAdapterInstance);
                    // last-known-gateway is not in via list, therefore no single result is returned
                    assertGetInstancesResultSize(result, 2);
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation succeeds if multiple adapter instances
     * have been registered for gateways of the given device, but the last known gateway associated with the given
     * device has no adapter associated with it.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesWithMultiResultAndNoAdapterForLastKnownGateway(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String adapterInstance = "adapterInstance";
        final String otherAdapterInstance = "otherAdapterInstance";
        final String gatewayId = "gw-1";
        final String otherGatewayId = "gw-2";
        final String gatewayWithNoAdapterInstance = "gw-other";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId, otherGatewayId, gatewayWithNoAdapterInstance));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice's last known gateway is set to gw-other
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture(gatewayWithNoAdapterInstance));

        // and command handling adapter instances are set for gw-1 and gw-2
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of(
                CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), adapterInstance,
                CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, otherGatewayId), otherAdapterInstance)));

        // but no command handling adapter instance is registered for testDevice nor gw-other
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, gatewayWithNoAdapterInstance))))
            .thenReturn(Future.succeededFuture(Map.of()));

        // WHEN retrieving command handling adapter instances for testDevice
        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertThat(result).isNotNull();
                    assertGetInstancesResultMapping(result, gatewayId, adapterInstance);
                    assertGetInstancesResultMapping(result, otherGatewayId, otherAdapterInstance);
                    // no adapter registered for last-known-gateway, therefore no single result is returned
                    assertGetInstancesResultSize(result, 2);
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation fails if an adapter instance has
     * been registered for the last known gateway associated with the given device, but that gateway isn't in the
     * given viaGateways set.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesForSingleResultAndLastKnownGatewayNotInVia(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String adapterInstance = "adapterInstance";
        final String gatewayId = "gw-1";
        final Set<String> viaGateways = new HashSet<>(Set.of("otherGatewayId"));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice's last known gateway is set to gw-1
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture(gatewayId));

        // and a command handling adapter instance is set for gw-1 only
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, gatewayId))))
            .thenReturn(Future.succeededFuture(Map.of(
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), adapterInstance)));
        // but not for the gateways in the via list
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of()));

        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, gatewayId))))
            .thenReturn(Future.succeededFuture(Map.of(
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), adapterInstance)));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> {
                    assertThat(t).isInstanceOfSatisfying(
                            ClientErrorException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation succeeds with a result containing just
     * the mapping of *the given device* to its command handling adapter instance, even though an adapter instance is
     * also registered for the last known gateway associated with the given device.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesWithLastKnownGatewayIsGivingDevicePrecedence(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {
        final String deviceId = "testDevice";
        final String adapterInstance = "adapterInstance";
        final String otherAdapterInstance = "otherAdapterInstance";
        final String gatewayId = "gw-1";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice's last known gateway is set to gw-1
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture(gatewayId));

        // and testDevice's and gw-1's command handling adapter instances are set to
        // adapterInstance and otherAdapterInstance respectively
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, gatewayId))))
            .thenReturn(Future.succeededFuture(Map.of(
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, deviceId), adapterInstance,
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), otherAdapterInstance)));

        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of(
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, deviceId), adapterInstance,
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), otherAdapterInstance)));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertThat(result).isNotNull();
                    assertGetInstancesResultMapping(result, deviceId, adapterInstance);
                    // be sure that only the mapping for the device is returned, not the mappings for the gateway
                    assertGetInstancesResultSize(result, 1);
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation succeeds with a result containing just
     * the mapping of *the given device* to its command handling adapter instance, even though an adapter instance is
     * also registered for the other gateway given in the viaGateway.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesWithoutLastKnownGatewayIsGivingDevicePrecedence(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String adapterInstance = "adapterInstance";
        final String otherAdapterInstance = "otherAdapterInstance";
        final String gatewayId = "gw-1";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice has no last known gateway registered
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture());

        // and testDevice's and gw-1's command handling adapter instances are set to
        // adapterInstance and otherAdapterInstance respectively
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of(
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, deviceId), adapterInstance,
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), otherAdapterInstance)));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertNotNull(result);
                    assertGetInstancesResultMapping(result, deviceId, adapterInstance);
                    // be sure that only the mapping for the device is returned, not the mappings for the gateway
                    assertGetInstancesResultSize(result, 1);
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation succeeds with a result containing
     * the mapping of a gateway, even though there is no last known gateway set for the device.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesForOneSubscribedVia(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String adapterInstance = "adapterInstance";
        final String gatewayId = "gw-1";
        final String otherGatewayId = "gw-2";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId, otherGatewayId));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice has no last known gateway registered
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture());

        // and gw-1's command handling adapter instance is set to adapterInstance
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of(
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), adapterInstance)));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertNotNull(result);
                    assertGetInstancesResultMapping(result, gatewayId, adapterInstance);
                    assertGetInstancesResultSize(result, 1);
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation succeeds with a result containing
     * the mappings of gateways, even though there is no last known gateway set for the device.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesForMultipleSubscribedVias(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String adapterInstance = "adapterInstance";
        final String otherAdapterInstance = "otherAdapterInstance";
        final String gatewayId = "gw-1";
        final String otherGatewayId = "gw-2";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId, otherGatewayId));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice has no last known gateway registered
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture());

        // and gw-1's and gw-2's command handling adapter instance are set to
        // adapterInstance and otherAdapterInstance respectively
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of(
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, gatewayId), adapterInstance,
                    CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKey(Constants.DEFAULT_TENANT, otherGatewayId), otherAdapterInstance)));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertNotNull(result);
                    assertGetInstancesResultMapping(result, gatewayId, adapterInstance);
                    assertGetInstancesResultMapping(result, otherGatewayId, otherAdapterInstance);
                    assertGetInstancesResultSize(result, 2);
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation fails for a device with a
     * non-empty set of given viaGateways, if no matching instance has been registered.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesFailsWithGivenGateways(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String gatewayId = "gw-1";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice has no last known gateway registered
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture());

        // and gw-1 has no command handling adapter instance registered
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of()));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> {
                    assertThat(t).isInstanceOfSatisfying(
                            ClientErrorException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Verifies that the <em>getCommandHandlingAdapterInstances</em> operation fails if no matching instance
     * has been registered. An adapter instance has been registered for another device of the same tenant though.
     *
     * @param extraUnusedViaGateways Test values.
     * @param ctx The vert.x context.
     */
    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME_PATTERN)
    @MethodSource("extraUnusedViaGateways")
    public void testGetCommandHandlingAdapterInstancesFailsForOtherTenantDevice(final Set<String> extraUnusedViaGateways, final VertxTestContext ctx) {

        final String deviceId = "testDevice";
        final String gatewayId = "gw-1";
        final Set<String> viaGateways = new HashSet<>(Set.of(gatewayId));
        viaGateways.addAll(extraUnusedViaGateways);

        // GIVEN testDevice has no last known gateway registered
        when(cache.get(eq(CacheBasedDeviceConnectionInfo.getGatewayEntryKey(Constants.DEFAULT_TENANT, deviceId))))
            .thenReturn(Future.succeededFuture());

        // and gw-2's command handling adapter instance is set to adapterInstance
        when(cache.getAll(eq(CacheBasedDeviceConnectionInfo.getAdapterInstanceEntryKeys(Constants.DEFAULT_TENANT, deviceId, viaGateways))))
            .thenReturn(Future.succeededFuture(Map.of()));

        info.getCommandHandlingAdapterInstances(Constants.DEFAULT_TENANT, deviceId, viaGateways, span)
            .onComplete(ctx.failing(t -> {
                ctx.verify(() -> {
                    assertThat(t).isInstanceOfSatisfying(
                            ClientErrorException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND));
                });
                ctx.completeNow();
            }));
    }

    /**
     * Asserts the the given result JSON of the <em>getCommandHandlingAdapterInstances</em> method contains
     * an "adapter-instances" entry with the given device id and adapter instance id.
     */
    private void assertGetInstancesResultMapping(final JsonObject resultJson, final String deviceId, final String adapterInstanceId) {
        assertNotNull(resultJson);
        final JsonArray adapterInstancesJson = resultJson.getJsonArray(DeviceConnectionConstants.FIELD_ADAPTER_INSTANCES);
        assertNotNull(adapterInstancesJson);
        boolean entryFound = false;
        for (int i = 0; i < adapterInstancesJson.size(); i++) {
            final JsonObject entry = adapterInstancesJson.getJsonObject(i);
            if (deviceId.equals(entry.getString(DeviceConnectionConstants.FIELD_PAYLOAD_DEVICE_ID))) {
                entryFound = true;
                assertEquals(adapterInstanceId, entry.getString(DeviceConnectionConstants.FIELD_ADAPTER_INSTANCE_ID));
            }
        }
        assertTrue(entryFound);
    }

    private void assertGetInstancesResultSize(final JsonObject resultJson, final int size) {
        assertNotNull(resultJson);
        final JsonArray adapterInstancesJson = resultJson.getJsonArray(DeviceConnectionConstants.FIELD_ADAPTER_INSTANCES);
        assertNotNull(adapterInstancesJson);
        assertEquals(size, adapterInstancesJson.size());
    }

}
