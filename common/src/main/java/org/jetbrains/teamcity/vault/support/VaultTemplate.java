/*
 * Copyright 2016-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.teamcity.vault.support;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.teamcity.vault.UtilKt;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.Assert;
import org.springframework.vault.authentication.SessionManager;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.client.VaultHttpHeaders;
import org.springframework.vault.core.RestOperationsCallback;
import org.springframework.vault.support.VaultResponse;
import org.springframework.vault.support.VaultToken;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

/**
 * Based on {@link org.springframework.vault.core.VaultTemplate}
 *
 * This class encapsulates main Vault interaction. {@link VaultTemplate} will log into
 * Vault on initialization and use the token throughout the whole lifetime.
 *
 * @author Mark Paluch
 * @see SessionManager
 */
public class VaultTemplate {

    private final RestTemplate sessionTemplate;

    private final RestTemplate plainTemplate;

    /**
     * Create a new {@link VaultTemplate} with a {@link VaultEndpoint},
     * {@link ClientHttpRequestFactory} and {@link SessionManager}.
     *
     * @param vaultEndpoint            must not be {@literal null}.
     * @param clientHttpRequestFactory must not be {@literal null}.
     * @param sessionManager           must not be {@literal null}.
     */
    public VaultTemplate(@NotNull VaultEndpoint vaultEndpoint,
                         @NotNull String vaultNamespace,
                         @NotNull ClientHttpRequestFactory clientHttpRequestFactory,
                         @Nullable SessionManager sessionManager) {
        this.plainTemplate = UtilKt.createRestTemplate(vaultEndpoint, clientHttpRequestFactory);
        if (sessionManager != null) {
            this.sessionTemplate = createSessionTemplate(vaultEndpoint, clientHttpRequestFactory, sessionManager);
        } else {
            this.sessionTemplate = this.plainTemplate;
        }

        ClientHttpRequestInterceptor namespaceInterceptor = VaultInterceptors.createNamespaceInterceptor(vaultNamespace);
        if (namespaceInterceptor != null) {
            this.plainTemplate.getInterceptors().add(namespaceInterceptor);
            //noinspection ObjectEquality
            if (plainTemplate != sessionTemplate) {
                this.sessionTemplate.getInterceptors().add(namespaceInterceptor);
            }
        }
    }

    private static RestTemplate createSessionTemplate(@NotNull VaultEndpoint endpoint,
                                                      @NotNull ClientHttpRequestFactory requestFactory,
                                                      @NotNull final SessionManager sessionManager) {

        RestTemplate restTemplate = UtilKt.createRestTemplate(endpoint, requestFactory);

        restTemplate.getInterceptors().add(new ClientHttpRequestInterceptor() {

            @Override
            public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                                ClientHttpRequestExecution execution) throws IOException {

                final VaultToken sessionToken = sessionManager.getSessionToken();
                if (sessionToken != null) {
                    final String token = sessionToken.getToken();
                    if (token != null) {
                        request.getHeaders().set(VaultHttpHeaders.VAULT_TOKEN, token);
                    }
                }

                return execution.execute(request, body);
            }
        });

        return restTemplate;
    }

    public RestTemplate getDefaultTemplate() {
        return sessionTemplate;
    }

    public void wrapResponses(@NotNull final String wrapTTL) {
        final ClientHttpRequestInterceptor interceptor = new ClientHttpRequestInterceptor() {
            @Override
            public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
                request.getHeaders().set("X-Vault-Wrap-TTL", wrapTTL);
                return execution.execute(request, body);
            }
        };
        plainTemplate.getInterceptors().add(interceptor);
        sessionTemplate.getInterceptors().add(interceptor);
    }

    public VaultSysTemplate opsForSys() {
        return new VaultSysTemplate(this);
    }

    public VaultResponse read(String path) {

        Assert.hasText(path, "Path must not be empty");

        return doRead(path, VaultResponse.class);
    }


    public VaultResponse write(final String path, final Object body) {

        Assert.hasText(path, "Path must not be empty");

        try {
            return sessionTemplate.postForObject(path, body, VaultResponse.class);
        } catch (HttpStatusCodeException e) {
            throw VaultResponses.buildException(e, path);
        }
    }

    public <T> T doWithVault(RestOperationsCallback<T> clientCallback) {

        Assert.notNull(clientCallback, "Client callback must not be null");

        try {
            return clientCallback.doWithRestOperations(plainTemplate);
        } catch (HttpStatusCodeException e) {
            throw VaultResponses.buildException(e);
        }
    }

    public <T> T doWithSession(RestOperationsCallback<T> sessionCallback) {

        Assert.notNull(sessionCallback, "Session callback must not be null");

        try {
            return sessionCallback.doWithRestOperations(sessionTemplate);
        } catch (HttpStatusCodeException e) {
            throw VaultResponses.buildException(e);
        }
    }

    private <T> T doRead(final String path, final Class<T> responseType) {

        return doWithSession(new RestOperationsCallback<T>() {

            @Override
            public T doWithRestOperations(RestOperations restOperations) {

                try {
                    return restOperations.getForObject(path, responseType);
                } catch (HttpStatusCodeException e) {

                    if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                        return null;
                    }

                    throw VaultResponses.buildException(e, path);
                }
            }
        });
    }
}
