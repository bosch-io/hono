/*******************************************************************************
 * Copyright (c) 2016, 2018 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.tests.amqp;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Base64;
import java.util.UUID;

import javax.security.auth.x500.X500Principal;

import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.TenantConstants;
import org.eclipse.hono.util.TenantObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

/**
 * Integration tests for checking connection to the AMQP adapter.
 *
 */
@RunWith(VertxUnitRunner.class)
public class AmqpConnectionIT extends AmqpAdapterTestBase {

    /**
     * Logs the currently executing test method name.
     */
    @Before
    public void setup() {

        log.info("running {}", testName.getMethodName());
    }

    /**
     * Closes the connection to the adapter.
     */
    @After
    public void disconnnect() {
        if (connection != null) {
            connection.closeHandler(null);
            connection.close();
        }
    }

    /**
     * Verifies that the adapter opens a connection to registered devices with credentials.
     *
     * @param ctx The test context
     */
    @Test
    public void testConnectSucceedsForRegisteredDevice(final TestContext ctx) {
        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final String password = "secret";
        final TenantObject tenant = TenantObject.from(tenantId, true);

        helper.registry
        .addDeviceForTenant(tenant, deviceId, password)
        .compose(ok -> connectToAdapter(IntegrationTestSupport.getUsername(deviceId, tenantId), password))
        .setHandler(ctx.asyncAssertSuccess());
    }

    /**
     * Verifies that the adapter rejects connection attempts from unknown devices
     * for which neither registration information nor credentials are on record.
     *
     * @param ctx The test context
     */
    @Test
    public void testConnectFailsForNonExistingDevice(final TestContext ctx) {

        // GIVEN an adapter
        // WHEN an unknown device tries to connect
        connectToAdapter(IntegrationTestSupport.getUsername("non-existing", Constants.DEFAULT_TENANT), "secret")
        .setHandler(ctx.asyncAssertFailure(t -> {
            // THEN the connection is refused
            ctx.assertTrue(t instanceof SecurityException);
        }));
    }

    /**
     * Verifies that the adapter rejects connection attempts from devices
     * using wrong credentials.
     *
     * @param ctx The test context
     */
    @Test
    public void testConnectFailsForWrongCredentials(final TestContext ctx) {

        // GIVEN a registered device
        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final String password = "secret";
        final TenantObject tenant = TenantObject.from(tenantId, true);

        helper.registry
        .addDeviceForTenant(tenant, deviceId, password)
        // WHEN the device tries to connect using a wrong password
        .compose(ok -> connectToAdapter(IntegrationTestSupport.getUsername(deviceId, tenantId), "wrong password"))
        .setHandler(ctx.asyncAssertFailure(t -> {
            // THEN the connection is refused
            ctx.assertTrue(t instanceof SecurityException);
        }));
    }

    /**
     * Verifies that the adapter rejects connection attempts from devices belonging
     * to a tenant for which the AMQP adapter has been disabled.
     *
     * @param ctx The test context
     */
    @Test
    public void testConnectFailsForDisabledAdapter(final TestContext ctx) {

        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final String password = "secret";
        final TenantObject tenant = TenantObject.from(tenantId, true);
        final JsonObject adapterDetailsMqtt = new JsonObject()
                .put(TenantConstants.FIELD_ADAPTERS_TYPE, Constants.PROTOCOL_ADAPTER_TYPE_HTTP)
                .put(TenantConstants.FIELD_ENABLED, Boolean.TRUE);
        tenant.addAdapterConfiguration(adapterDetailsMqtt);

        helper.registry.addDeviceForTenant(tenant, deviceId, password)
        // WHEN a device that belongs to the tenant tries to connect to the adapter
        .compose(ok -> connectToAdapter(IntegrationTestSupport.getUsername(deviceId, tenantId), password))
        .setHandler(ctx.asyncAssertFailure(t -> {
                // THEN the connection is refused
            ctx.assertTrue(t instanceof SecurityException);
         }));
    }

    /**
     * Verifies that the adapter rejects connection attempts from devices for which
     * credentials exist but for which no registration assertion can be retrieved.
     *
     * @param ctx The test context
     */
    @Test
    public void testConnectFailsForDeletedDevices(final TestContext ctx) {

        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final String password = "secret";
        final TenantObject tenant = TenantObject.from(tenantId, true);

        helper.registry
            .addDeviceForTenant(tenant, deviceId, password)
            .compose(device -> helper.registry.deregisterDevice(tenantId, deviceId))
            .compose(ok -> connectToAdapter(IntegrationTestSupport.getUsername(deviceId, tenantId), password))
            .setHandler(ctx.asyncAssertFailure(t -> {
                // THEN the connection is refused
            }));
    }

    /**
     * Verifies that the AMQP Adapter will fail to authenticate a device whose username does not match the expected pattern
     * {@code [<authId>@<tenantId>]}.
     * 
     * @param context The Vert.x test context.
     */
    @Test
    public void testConnectFailsForInvalidUsernamePattern(final TestContext context) {

        // GIVEN an adapter with a registered device
        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final String password = "secret";
        final TenantObject tenant = TenantObject.from(tenantId, true);

        helper.registry.addDeviceForTenant(tenant, deviceId, password)
        // WHEN the device tries to connect using a malformed username
        .compose(ok -> connectToAdapter(deviceId, "secret"))
        .setHandler(context.asyncAssertFailure(t -> {
            // THEN the SASL handshake fails
            context.assertTrue(t instanceof SecurityException);
        }));
    }

    /**
     * Verifies that the adapter fails to authenticate a device if the device's client
     * certificate's signature cannot be validated using the trust anchor that is registered
     * for the tenant that the device belongs to.
     *
     * @param ctx The test context.
     * @throws GeneralSecurityException if the tenant's trust anchor cannot be generated
     */
    @Test
    public void testConnectFailsForNonMatchingTrustAnchor(final TestContext ctx) throws GeneralSecurityException {

        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final TenantObject tenant = TenantObject.from(tenantId, true);

        final KeyPair keyPair = helper.newEcKeyPair();
        final SelfSignedCertificate deviceCert = SelfSignedCertificate.create(UUID.randomUUID().toString());

        // GIVEN a tenant configured with a trust anchor
        helper.getCertificate(deviceCert.certificatePath())
        .compose(cert -> {
            tenant.setProperty(
                    TenantConstants.FIELD_PAYLOAD_TRUSTED_CA,
                    new JsonObject()
                        .put(TenantConstants.FIELD_PAYLOAD_SUBJECT_DN, cert.getIssuerX500Principal().getName(X500Principal.RFC2253))
                        .put(TenantConstants.FIELD_ADAPTERS_TYPE, "EC")
                        .put(TenantConstants.FIELD_PAYLOAD_PUBLIC_KEY, Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())));
            return helper.registry.addDeviceForTenant(tenant, deviceId, cert);
        })
        .compose(ok -> {
            // WHEN a device tries to connect to the adapter
            // using a client certificate that cannot be validated
            // using the trust anchor registered for the device's tenant
            return connectToAdapter(deviceCert);
        })
        .setHandler(ctx.asyncAssertFailure(t -> {
            // THEN the connection is not established
            ctx.assertTrue(t instanceof SecurityException);
        }));
    }

    /**
     * Verifies that the adapter fails to authorize a device using a client certificate
     * if the public key that is registered for the tenant that the device belongs to can
     * not be parsed into a trust anchor.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testConnectFailsForMalformedCaPublicKey(final TestContext ctx) {

        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final TenantObject tenant = TenantObject.from(tenantId, true);
        final SelfSignedCertificate deviceCert = SelfSignedCertificate.create(UUID.randomUUID().toString());

        // GIVEN a tenant configured with an invalid Base64 encoding of the
        // trust anchor public key
        helper.getCertificate(deviceCert.certificatePath())
        .compose(cert -> {
            tenant.setProperty(
                    TenantConstants.FIELD_PAYLOAD_TRUSTED_CA,
                    new JsonObject()
                        .put(TenantConstants.FIELD_PAYLOAD_SUBJECT_DN, cert.getIssuerX500Principal().getName(X500Principal.RFC2253))
                        .put(TenantConstants.FIELD_PAYLOAD_PUBLIC_KEY, "notBase64"));
            return helper.registry.addDeviceForTenant(tenant, deviceId, cert);
        })
        .compose(ok -> {
            // WHEN a device tries to connect to the adapter
            // using a client certificate
            return connectToAdapter(deviceCert);
        })
        .setHandler(ctx.asyncAssertFailure(t -> {
            // THEN the connection is not established
            ctx.assertTrue(t instanceof SecurityException);
        }));
    }
}
