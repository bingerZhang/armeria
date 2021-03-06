/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client.thrift;

import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryProvider;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.client.http.HttpClientFactory;

/**
 * {@link ClientFactoryProvider} that creates a {@link THttpClientFactory}.
 */
public final class THttpClientFactoryProvider implements ClientFactoryProvider {
    @Override
    public ClientFactory newFactory(SessionOptions options, Map<Class<?>, ClientFactory> dependencies) {
        return new THttpClientFactory(dependencies.get(HttpClientFactory.class));
    }

    @Override
    public Set<Class<? extends ClientFactory>> dependencies() {
        return ImmutableSet.of(HttpClientFactory.class);
    }
}
