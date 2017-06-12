/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.remoting.api.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.palantir.remoting.api.config.ssl.SslConfiguration;
import com.palantir.remoting.api.ext.jackson.ObjectMappers;
import com.palantir.remoting.api.ext.jackson.ShimJdk7Module;
import com.palantir.tokens2.auth.BearerToken;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import org.junit.Test;

public final class ServiceConfigurationFactoryTests {

    private static final BearerToken apiToken = BearerToken.valueOf("token");
    private static final BearerToken defaultApiToken = BearerToken.valueOf("defaultToken");
    private static final SslConfiguration security = SslConfiguration.of(mock(Path.class));
    private static final SslConfiguration defaultSecurity = SslConfiguration.of(mock(Path.class));
    private static final HumanReadableDuration connectTimeout = HumanReadableDuration.seconds(1);
    private static final HumanReadableDuration defaultConnectTimeout = HumanReadableDuration.seconds(2);
    private static final HumanReadableDuration readTimeout = HumanReadableDuration.minutes(1);
    private static final HumanReadableDuration defaultReadTimeout = HumanReadableDuration.minutes(2);
    private static final HumanReadableDuration writeTimeout = HumanReadableDuration.days(1);
    private static final ProxyConfiguration proxy = ProxyConfiguration.DIRECT;
    private static final ProxyConfiguration defaultProxyConfiguration = ProxyConfiguration.of("globalsquid:3128");
    private static final ImmutableList<String> uris = ImmutableList.of("uri");
    private static final boolean defaultEnableGcm = true;
    private static final boolean enableGcm = false;

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .registerModule(new ShimJdk7Module())
            .registerModule(new Jdk8Module());

    @Test
    public void testIsServiceEnabled_noEnabledIfNoUrisOrServiceDoesNotExist() throws IOException {
        ServiceConfigurationFactory factory = ServiceConfigurationFactory.of(
                deserialize("configs/discovery-config-with-empty-uri.yml"));

        assertThat(factory.isEnabled("service1")).isFalse();
        assertThat(factory.isEnabled("service2")).isTrue();
        assertThat(factory.isEnabled("foobar")).isFalse();
    }

    @Test
    public void testDeserializationAndFallbackSanity() throws Exception {
        ServicesConfigBlock services = deserialize("configs/discovery-config-with-fallback.yml");
        ServiceConfiguration service1 = ServiceConfigurationFactory.of(services).get("service1");
        ServiceConfiguration service2 = ServiceConfigurationFactory.of(services).get("service2");
        ServiceConfiguration service3 = ServiceConfigurationFactory.of(services).get("service3");

        assertThat(service1.apiToken()).contains(BearerToken.valueOf("service1ApiToken"));
        assertThat(service2.apiToken()).contains(BearerToken.valueOf("service2ApiToken"));
        assertThat(service3.apiToken()).contains(BearerToken.valueOf("defaultApiToken"));
    }

    @Test
    public void testUsesCompileTimeDefaultConfigurationWhenNothingElseIsGiven() {
        PartialServiceConfiguration partial = PartialServiceConfiguration.of(uris, Optional.empty());
        ServicesConfigBlock services = ServicesConfigBlock.builder()
                .putAllServices(ImmutableMap.of("service1", partial))
                .defaultSecurity(defaultSecurity)
                .build();
        ServiceConfiguration actual = ServiceConfigurationFactory.of(services).get("service1");

        // The following are the compile-time defaults.
        ServiceConfiguration expected = ImmutableServiceConfiguration.builder()
                .security(defaultSecurity)
                .uris(uris)
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(10))
                .writeTimeout(Duration.ofMinutes(10))
                .enableGcmCipherSuites(false)
                .proxy(ProxyConfiguration.SYSTEM)
                .maxNumRetries(0)
                .build();

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void testUsesDefaultConfigurationWhenNoExplicitConfigIsGiven() {
        PartialServiceConfiguration partial = PartialServiceConfiguration.of(uris, Optional.empty());
        ServicesConfigBlock services = ServicesConfigBlock.builder()
                .putAllServices(ImmutableMap.of("service1", partial))
                .defaultSecurity(defaultSecurity)
                .defaultApiToken(defaultApiToken)
                .defaultProxyConfiguration(defaultProxyConfiguration)
                .defaultConnectTimeout(defaultConnectTimeout)
                .defaultReadTimeout(defaultReadTimeout)
                .defaultEnableGcmCipherSuites(defaultEnableGcm)
                .build();
        ServiceConfiguration service = ServiceConfigurationFactory.of(services).get("service1");

        ServiceConfiguration expected = ImmutableServiceConfiguration.builder()
                .apiToken(defaultApiToken)
                .security(defaultSecurity)
                .uris(uris)
                .connectTimeout(Duration.ofSeconds(defaultConnectTimeout.toSeconds()))
                .readTimeout(Duration.ofMinutes(defaultReadTimeout.toMinutes()))
                .writeTimeout(Duration.ofMinutes(10))  // doesn't exist in services config
                .enableGcmCipherSuites(defaultEnableGcm)
                .proxy(defaultProxyConfiguration)
                .maxNumRetries(0)  // doesn't exist in services config
                .build();

        assertThat(service).isEqualTo(expected);
    }

    @Test
    public void testServiceSpecificConfigTrumpsDefaultConfig() {
        PartialServiceConfiguration partial = PartialServiceConfiguration.builder()
                .apiToken(apiToken)
                .uris(uris)
                .security(security)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .writeTimeout(writeTimeout)
                .enableGcmCipherSuites(enableGcm)
                .proxyConfiguration(proxy)
                .build();
        ServicesConfigBlock services = ServicesConfigBlock.builder()
                .putAllServices(ImmutableMap.of("service1", partial))
                .defaultSecurity(defaultSecurity)
                .defaultApiToken(defaultApiToken)
                .defaultProxyConfiguration(defaultProxyConfiguration)
                .defaultConnectTimeout(defaultConnectTimeout)
                .defaultReadTimeout(defaultReadTimeout)
                .defaultEnableGcmCipherSuites(defaultEnableGcm)
                .build();
        ServiceConfiguration service = ServiceConfigurationFactory.of(services).get("service1");

        ServiceConfiguration expected = ImmutableServiceConfiguration.builder()
                .apiToken(apiToken)
                .security(security)
                .uris(uris)
                .connectTimeout(Duration.ofSeconds(connectTimeout.toSeconds()))
                .readTimeout(Duration.ofMinutes(readTimeout.toMinutes()))
                .writeTimeout(Duration.ofHours(writeTimeout.toHours()))
                .enableGcmCipherSuites(enableGcm)
                .proxy(proxy)
                .maxNumRetries(0)
                .build();

        assertThat(service).isEqualTo(expected);
    }

    @Test
    public void serDe() throws Exception {
        ServicesConfigBlock serialized = ServicesConfigBlock.builder()
                .defaultApiToken(BearerToken.valueOf("bearerToken"))
                .defaultSecurity(SslConfiguration.of(Paths.get("truststore.jks")))
                .putServices("service", PartialServiceConfiguration.of(uris, Optional.empty()))
                .defaultProxyConfiguration(ProxyConfiguration.of("host:80"))
                .defaultConnectTimeout(HumanReadableDuration.days(1))
                .defaultReadTimeout(HumanReadableDuration.days(1))
                .build();
        String camelCase = "{\"apiToken\":\"bearerToken\",\"security\":"
                + "{\"trustStorePath\":\"truststore.jks\",\"trustStoreType\":\"JKS\",\"keyStorePath\":null,"
                + "\"keyStorePassword\":null,\"keyStoreType\":\"JKS\",\"keyStoreKeyAlias\":null},\"services\":"
                + "{\"service\":{\"apiToken\":null,\"security\":null,\"uris\":[\"uri\"],\"connectTimeout\":null,"
                + "\"readTimeout\":null,\"writeTimeout\":null,\"enableGcmCipherSuites\":null,"
                + "\"proxyConfiguration\":null}},\"proxyConfiguration\":"
                + "{\"hostAndPort\":\"host:80\",\"credentials\":null,\"type\":\"HTTP\"},\"connectTimeout\":\"1 day\","
                + "\"readTimeout\":\"1 day\",\"enableGcmCipherSuites\":null}";
        String kebabCase = "{\"api-token\":\"bearerToken\",\"security\":"
                + "{\"trust-store-path\":\"truststore.jks\",\"trust-store-type\":\"JKS\",\"key-store-path\":null,"
                + "\"key-store-password\":null,\"key-store-type\":\"JKS\",\"key-store-key-alias\":null},\"services\":"
                + "{\"service\":{\"apiToken\":null,\"security\":null,\"connect-timeout\":null,\"read-timeout\":null,"
                + "\"write-timeout\":null,\"uris\":[\"uri\"],\"enable-gcm-cipher-suites\":null,"
                + "\"proxy-configuration\":null}},\"proxy-configuration\":"
                + "{\"host-and-port\":\"host:80\",\"credentials\":null},\"connect-timeout\":\"1 day\","
                + "\"read-timeout\":\"1 day\"}";

        assertThat(ObjectMappers.newClientObjectMapper().writeValueAsString(serialized))
                .isEqualTo(camelCase);
        assertThat(ObjectMappers.newClientObjectMapper()
                .readValue(camelCase, ServicesConfigBlock.class))
                .isEqualTo(serialized);
        assertThat(ObjectMappers.newClientObjectMapper()
                .readValue(kebabCase, ServicesConfigBlock.class))
                .isEqualTo(serialized);
    }

    @Test
    public void serDe_optional() throws Exception {
        ServicesConfigBlock serialized = ServicesConfigBlock.builder().build();
        String deserializedCamelCase = "{\"apiToken\":null,\"security\":null,\"services\":{},"
                + "\"proxyConfiguration\":null,\"connectTimeout\":null,\"readTimeout\":null,"
                + "\"enableGcmCipherSuites\":null}";
        String deserializedKebabCase = "{\"api-token\":null,\"security\":null,\"services\":{},"
                + "\"proxy-configuration\":null,\"connect-timeout\":null,\"read-timeout\":null,"
                + "\"enable-gcm-cipher-suites\":null}";

        assertThat(ObjectMappers.newClientObjectMapper().writeValueAsString(serialized))
                .isEqualTo(deserializedCamelCase);
        assertThat(ObjectMappers.newClientObjectMapper()
                .readValue(deserializedCamelCase, ServicesConfigBlock.class))
                .isEqualTo(serialized);
        assertThat(ObjectMappers.newClientObjectMapper()
                .readValue(deserializedKebabCase, ServicesConfigBlock.class))
                .isEqualTo(serialized);
    }

    private ServicesConfigBlock deserialize(String file) {
        URL resource = Resources.getResource(file);
        try {
            return mapper.readValue(resource.openStream(), ServicesConfigBlock.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open file: " + file);
        }
    }
}
