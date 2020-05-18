/*******************************************************************************
 * Copyright (c) 2019, 2020 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.adapter.lora.providers;

/**
 * Verifies behavior of {@link ObjeniousProvider}.
 */
public class ObjeniousProviderTest extends LoraProviderTestBase<ObjeniousProvider> {


    /**
     * {@inheritDoc}
     */
    @Override
    protected ObjeniousProvider newProvider() {
        return new ObjeniousProvider();
    }
}
