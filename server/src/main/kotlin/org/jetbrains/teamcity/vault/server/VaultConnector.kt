/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.teamcity.vault.server

import com.amazonaws.SdkBaseException
import com.amazonaws.auth.InstanceProfileCredentialsProvider
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.util.concurrent.Striped
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.containers.ConcurrentHashSet
import jetbrains.buildServer.log.Loggers
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.BuildServerListener
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SRunningBuild
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.util.ssl.SSLTrustStoreProvider
import org.jetbrains.teamcity.vault.*
import org.jetbrains.teamcity.vault.support.VaultResponses
import org.jetbrains.teamcity.vault.support.VaultTemplate
import org.springframework.http.HttpStatus
import org.springframework.vault.VaultException
import org.springframework.vault.authentication.AppRoleAuthenticationOptions
import org.springframework.vault.authentication.AwsIamAuthentication
import org.springframework.vault.authentication.AwsIamAuthenticationOptions
import org.springframework.vault.client.VaultEndpoint
import org.springframework.vault.support.VaultResponse
import org.springframework.vault.support.VaultToken
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

class VaultConnector(dispatcher: EventDispatcher<BuildServerListener>, private val trustStoreProvider: SSLTrustStoreProvider) {
    init {
        dispatcher.addListener(object : BuildServerAdapter() {
            override fun buildFinished(build: SRunningBuild) {
                val infos = myBuildsTokens.remove(build.buildId) ?: return
                infos.values.forEach { info ->
                    if (info == LeasedWrappedTokenInfo.FAILED_TO_FETCH) return
                    myPendingRemoval.add(info)
                    if (revoke(info, trustStoreProvider)) {
                        myPendingRemoval.remove(info)
                    }
                }
            }
        })
    }

    companion object {
        val LOG = Logger.getInstance(Loggers.SERVER_CATEGORY + "." + VaultConnector::class.java.name)!!

        /**
         * @return true if operation succeed
         */
        @JvmStatic
        fun revoke(info: LeasedWrappedTokenInfo, trustStoreProvider: SSLTrustStoreProvider, catch: Boolean = true): Boolean {
            val settings = info.connection
            if (settings.auth.method == AuthMethod.AWS_IAM) {
                // AWS IAM auth uses separate tokens, so we cannot revoke agent token from server
                // we don't even request wrapped token before build
                return true
            }
            assert(settings.auth.method == AuthMethod.APPROLE)
            try {
                val template = createRestTemplate(settings, trustStoreProvider)
                // Login and retrieve server token
                val (token, _) = performLogin(template, settings, extractTokenAndAccessor)

                template.withVaultToken(token)
                try {
                    // Revoke agent token
                    revokeAccessor(template, info.accessor, info.connection)
                } finally {
                    // Revoke server token we just obtained
                    revokeSelf(template)
                }
                return true
            } catch (e: Exception) {
                LOG.warnAndDebugDetails("Failed to revoke token", e)
                if (!catch) throw e
            }
            return false
        }

        /**
         * @return true if operation succeed
         */
        @JvmStatic
        fun revoke(info: LeasedTokenInfo, trustStoreProvider: SSLTrustStoreProvider): Boolean {
            val settings = info.connection
            try {
                val template = createRestTemplate(settings, trustStoreProvider)
                template.withVaultToken(info.token)
                // Revoke token
                return revokeSelf(template)
            } catch (e: Exception) {
                LOG.warnAndDebugDetails("Failed to revoke token", e)
            }
            return false
        }

        private fun getTokenFromAwsIamAuth(template: RestTemplate): Pair<String, String> {
            val options = AwsIamAuthenticationOptions.builder()
                    .credentialsProvider(InstanceProfileCredentialsProvider.getInstance()).build()

            val token: VaultToken
            val aws = AwsIamAuthentication(options, template)
            try {
                token = aws.login()
                template.withVaultToken(token.token)
                val response = template.getForEntity("/auth/token/lookup-self", VaultResponse::class.java)
                val data = response.body.data
                val accessor = data["accessor"].toString()

                return token.token to accessor
            } catch (e: SdkBaseException) {
                throw ConnectionException("Failed to login to AWS IAM", e)
            } catch (e: VaultException) {
                val cause = e.cause
                if (cause is HttpStatusCodeException) {
                    throw getReadableException(cause)
                }
                throw e
            }
        }

        /**
         * @return true if operation succeed or it doesn't makes sense to try again later
         */
        private fun revokeAccessor(template: RestTemplate, accessor: String, settings: VaultFeatureSettings): Boolean {
            template.errorHandler = object : DefaultResponseErrorHandler() {
                override fun hasError(statusCode: HttpStatus?): Boolean {
                    if (statusCode == HttpStatus.FORBIDDEN || statusCode == HttpStatus.BAD_REQUEST) return false
                    return super.hasError(statusCode)
                }
            }
            val entity = template.postForEntity("/auth/token/revoke-accessor", mapOf("accessor" to accessor), ObjectNode::class.java)
            if (entity.statusCode == HttpStatus.NO_CONTENT) {
                // OK
                return true
            }
            val error = VaultResponses.getError(entity.body)
            val suffix = error?.replace('\n', ' ')?.let { ". Error message: $it" } ?: ""
            if (entity.statusCode == HttpStatus.FORBIDDEN) {
                when (settings.auth.method) {
                    AuthMethod.APPROLE -> LOG.warn("Failed to revoke token via accessor '$accessor': access denied, give approle '${(settings.auth as Auth.AppRoleAuthServer).roleId}' 'update' access to '/auth/token/revoke-accessor'$suffix")
                    AuthMethod.AWS_IAM -> LOG.warn("Failed to revoke token via accessor '$accessor': access denied, give AWS IAM role access to '/auth/token/revoke-accessor'$suffix")
                }
                return true
            }
            if (entity.statusCode == HttpStatus.BAD_REQUEST) {
                val message = "Failed to revoke token via accessor '$accessor': server returned 400, most probably token was already revoked$suffix"
                if (error?.contains("invalid accessor") == true) {
                    LOG.info(message)
                } else {
                    LOG.warn(message)
                }
                return true
            }
            LOG.warn("Unexpected response from Hashicorp Vault during token accessor revocation: ${entity.statusCodeValue}")
            return false
        }

        /**
         * @return true if operation succeed
         */
        private fun revokeSelf(template: RestTemplate): Boolean {
            val backoffs = intArrayOf(1, 3, 6, 0) // last is not used
            var e: Exception? = null
            for (backoff in backoffs) {
                try {
                    template.postForObject("auth/token/revoke-self", null, ObjectNode::class.java)
                    return true
                } catch (re: RuntimeException) {
                    e = re
                    try {
                        TimeUnit.SECONDS.sleep(backoff.toLong())
                    } catch (ignored: InterruptedException) {
                    }
                }
            }
            var message: String? = "Cannot revoke HashiCorp Vault token: "
            if (e is HttpStatusCodeException) {
                message += VaultResponses.getError((e as HttpStatusCodeException?)!!)
            } else {
                message += e?.message
            }
            LOG.warn(message, e)
            return false
        }

        private fun getAppRoleLogin(appRoleAuth: Auth.AppRoleAuthServer): Map<String, String> {
            val login = HashMap<String, String>(2)
            login["role_id"] = appRoleAuth.roleId
            appRoleAuth.secretId.nullIfEmpty()?.let {
                login["secret_id"] = it
            }
            return login
        }

        private fun getReadableException(cause: HttpStatusCodeException, replacer: ((String) -> String)? = null): ConnectionException {
            val err = VaultResponses.getError(cause)
            val prefix = "Cannot log in to HashiCorp Vault using AppRole credentials"
            val message: String
            message = setOf("failed to validate credentials: ", "failed to validate SecretID: ")
                    .find { err.startsWith(it) }
                    ?.let {
                        val suberror = err.removePrefix(it)
                        if (suberror.contains("invalid secret_id")) {
                            return@let "$prefix, SecretID is incorrect or expired"
                        } else if (suberror.contains("failed to find secondary index for role_id")) {
                            return@let "$prefix, RoleID is incorrect or there's no such role"
                        }
                        return@let null
                    } ?: "$prefix: $err"
            return ConnectionException(if (replacer != null) replacer(message) else message, cause)
        }

        @JvmStatic fun doRequestWrappedToken(settings: VaultFeatureSettings, trustStoreProvider: SSLTrustStoreProvider): Pair<String, String> {
            val endpoint = VaultEndpoint.from(URI.create(settings.url))!!
            val factory = createClientHttpRequestFactory(trustStoreProvider)

            val template = VaultTemplate(endpoint, settings.vaultNamespace, factory, null)
            template.wrapResponses("10m")

            return performLogin(template.defaultTemplate, settings, extractWrappedTokenAndAccessor)
        }

        @JvmStatic
        fun doRequestToken(settings: VaultFeatureSettings, trustStoreProvider: SSLTrustStoreProvider): Pair<String, String> {
            val endpoint = VaultEndpoint.from(URI.create(settings.url))!!
            val factory = createClientHttpRequestFactory(trustStoreProvider)

            val template = VaultTemplate(endpoint, settings.vaultNamespace, factory, null)

            return performLogin(template.defaultTemplate, settings, extractTokenAndAccessor)
        }

        private fun performLogin(template: RestTemplate, settings: VaultFeatureSettings, extractor: (VaultResponse) -> Pair<String, String>): Pair<String, String> {
            val appRoleAuth = settings.auth as Auth.AppRoleAuthServer

            val options = AppRoleAuthenticationOptions.builder()
                    .path(appRoleAuth.getNormalizedEndpoint())
                    .roleId(appRoleAuth.roleId)
                    .secretId(appRoleAuth.secretId)
                    .build()
            val login = getAppRoleLogin(appRoleAuth)

            try {
                val path = "auth/${options.path}/login"
                val vaultResponse = template.write(path, login)
                        ?: throw VaultException("HashiCorp Vault hasn't returned anything from POST to '$path'")

                return extractor(vaultResponse)
            } catch (e: VaultException) {
                val cause = e.cause
                if (cause is HttpStatusCodeException) {
                    throw getReadableException(cause) { it.replace(appRoleAuth.secretId, "*******") }
                }
                throw e
            }
        }

        private val extractTokenAndAccessor: (VaultResponse) -> Pair<String, String> = { response: VaultResponse ->
            val auth = response.auth

            val token = auth["client_token"] as? String
                    ?: throw VaultException("HashiCorp Vault hasn't returned token")
            val accessor = auth["accessor"] as? String
                    ?: throw VaultException("HashiCorp Vault hasn't returned token accessor")
            token to accessor
        }

        private val extractWrappedTokenAndAccessor: (VaultResponse) -> Pair<String, String> = { response: VaultResponse ->
            val wrap = response.wrapInfo
                    ?: throw VaultException("HashiCorp Vault hasn't returned 'wrap_info'")

            val token = wrap["token"]
                    ?: throw VaultException("HashiCorp Vault hasn't returned wrapped token")
            val accessor = wrap["wrapped_accessor"]
                    ?: throw VaultException("HashiCorp Vault hasn't returned wrapped token accessor")

            token to accessor
        }
    }

    // TODO: Support server restart
    private val myBuildsTokens: MutableMap<Long, MutableMap<String, LeasedWrappedTokenInfo>> = ConcurrentHashMap()
    private val myPendingRemoval: MutableSet<LeasedWrappedTokenInfo> = ConcurrentHashSet()
    @Suppress("UnstableApiUsage")
    private val myLocks = Striped.lazyWeakLock(64)

    fun requestWrappedToken(build: SBuild, settings: VaultFeatureSettings): String {
        val infos = myBuildsTokens.getOrDefault(build.buildId, ConcurrentHashMap())
        val info = infos[settings.namespace]
        if (info != null) return info.wrapped

        @Suppress("UnstableApiUsage")
        myLocks.get(build.buildId).withLock {
            @Suppress("NAME_SHADOWING")
            val infos = myBuildsTokens.getOrDefault(build.buildId,ConcurrentHashMap())
            @Suppress("NAME_SHADOWING")
            val info = infos[settings.namespace]
            if(info != null) return info.wrapped
            try {
                val (token, accessor) = doRequestWrappedToken(settings, trustStoreProvider)
                infos[settings.namespace] = LeasedWrappedTokenInfo(token, accessor, settings)
                myBuildsTokens[build.buildId] = infos
                return token
            } catch (e: Exception) {
                infos[settings.namespace] = LeasedWrappedTokenInfo.FAILED_TO_FETCH
                myBuildsTokens[build.buildId] = infos
                throw e
            }
        }
    }

    fun tryRequestToken(settings: VaultFeatureSettings): LeasedTokenInfo {
        return when (settings.auth.method) {
            AuthMethod.APPROLE -> {
                val (token, accessor) = doRequestToken(settings, trustStoreProvider)
                LeasedTokenInfo(token, accessor, settings)
            }
            AuthMethod.AWS_IAM -> {
                val template = createRestTemplate(settings, trustStoreProvider)
                val (token, accessor) = getTokenFromAwsIamAuth(template)
                LeasedTokenInfo(token, accessor, settings)
            }
        }
    }

    class ConnectionException(message: String, cause: Throwable) : Exception(message, cause)
}

data class LeasedWrappedTokenInfo(val wrapped: String, val accessor: String, val connection: VaultFeatureSettings) {
    companion object {
        val FAILED_TO_FETCH = LeasedWrappedTokenInfo(VaultConstants.SPECIAL_FAILED_TO_FETCH, "", VaultFeatureSettings(mapOf()))
    }
}

data class LeasedTokenInfo(val token: String, val accessor: String, val connection: VaultFeatureSettings)
