package io.kestra.plugin.scripts.runner.docker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.kestra.plugin.scripts.runner.docker.DockerService.*;

public class DockerRegistryAuthHttpClient implements DockerHttpClient {
    private static final Logger logger = LoggerFactory.getLogger(DockerRegistryAuthHttpClient.class);
    private static final Pattern AUTH_PATTERN = Pattern.compile("Bearer realm=\"([^\"]+)\",service=\"([^\"]+)\"(?:,scope=\"([^\"]+)\")?");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApacheDockerHttpClient delegate;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final DockerClientConfig config;

    public DockerRegistryAuthHttpClient(DockerClientConfig config) {
        this.config = config;
        this.delegate = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .build();
    }

    @Override
    public Response execute(Request request) {
        Response response = delegate.execute(request);

        if (response.getStatusCode() == 401) {
            String authHeader = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try {
                    if (response.getBody() != null) {
                        response.getBody().close();
                    }

                    String registryHost = extractRegistryFromPath(request.path());
                    String registryUrl = getEffectiveRegistryUrl(registryHost, request.path());

                    String token = getToken(authHeader, registryUrl);
                    Request newRequest = createAuthenticatedRequest(request, token);
                    return delegate.execute(newRequest);
                } catch (Exception e) {
                    logger.error("Error handling Docker registry authentication", e);
                }
            }
        }

        return response;
    }

    private String getEffectiveRegistryUrl(String registryHost, String path) {
        if (isDockerHubRegistry(registryHost) && path != null && path.contains("/v2/")) {
            return DOCKER_HUB_URL_V2;
        }

        return getRegistryUrl(registryHost);
    }

    private AuthConfig getEffectiveAuthConfig(String registryUrl) {
        AuthConfig authConfig = config.effectiveAuthConfig(registryUrl);

        if (authConfig == null && isDockerHubRegistry(registryUrl)) {
            if (registryUrl.contains("/v2")) {
                authConfig = config.effectiveAuthConfig(DOCKER_HUB_URL_V2);
            }

            if (authConfig == null) {
                authConfig = config.effectiveAuthConfig(DOCKER_HUB_URL_V1);
            }
        }

        return authConfig;
    }

    private String extractRegistryFromPath(String path) {
        if (path.startsWith("/v")) {
            int slashIndex = path.indexOf('/', 1);
            if (slashIndex > 0) {
                String registry = path.substring(slashIndex + 1);
                int nextSlash = registry.indexOf('/');
                if (nextSlash > 0) {
                    return registry.substring(0, nextSlash);
                }
                return registry;
            }
        }
        return "registry-1.docker.io"; // Default to Docker Hub
    }

    private String getToken(String authHeader, String registryUrl) throws IOException {
        Matcher matcher = AUTH_PATTERN.matcher(authHeader);
        if (!matcher.find()) {
            throw new IOException("Invalid authentication header format: " + authHeader);
        }

        String realm = matcher.group(1);
        String service = matcher.group(2);
        String scope = matcher.group(3);

        String cacheKey = service + ":" + (scope != null ? scope : "");

        CachedToken cachedToken = tokenCache.get(cacheKey);
        if (cachedToken != null && cachedToken.isValid()) {
            return cachedToken.token;
        }

        URI authUri = buildAuthUri(realm, service, scope);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(authUri);

            // Try to get auth credentials for this registry URL
            addBasicAuthIfNeeded(httpGet, registryUrl);

            try (ClassicHttpResponse authResponse = httpClient.executeOpen(null, httpGet, null)) {
                if (authResponse.getCode() != 200) {
                    throw new IOException("Failed to get auth token: " + authResponse.getCode() +
                                          " for registry: " + registryUrl);
                }

                TokenResponse tokenResponse = MAPPER.readValue(
                    EntityUtils.toByteArray(authResponse.getEntity()),
                    TokenResponse.class
                );

                long expiresAt = Instant.now().getEpochSecond() + tokenResponse.expiresIn;
                CachedToken newToken = new CachedToken(tokenResponse.token, expiresAt);
                tokenCache.put(cacheKey, newToken);

                return tokenResponse.token;
            }
        }
    }

    private URI buildAuthUri(String realm, String service, String scope) {
        StringBuilder uriBuilder = new StringBuilder(realm);
        uriBuilder.append("?service=").append(service);

        if (scope != null) {
            uriBuilder.append("&scope=").append(scope);
        }

        return URI.create(uriBuilder.toString());
    }

    private void addBasicAuthIfNeeded(HttpGet httpGet, String registryUrl) {
        AuthConfig authConfig = getEffectiveAuthConfig(registryUrl);

        if (authConfig != null) {
            String username = authConfig.getUsername();
            String password = authConfig.getPassword();

            if (username != null && !username.isEmpty() &&
                password != null && !password.isEmpty()) {
                String auth = username + ":" + password;
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
                httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);

                if (logger.isDebugEnabled()) {
                    logger.debug("Added basic auth for registry: {}", registryUrl);
                }
            }
        }
    }

    private Request createAuthenticatedRequest(Request original, String token) throws IOException {
        Request.Builder builder = Request.builder()
            .method(original.method())
            .path(original.path());

        Map<String, String> headers = new HashMap<>(original.headers());
        headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        builder.headers(headers);

        if (original.bodyBytes() != null) {
            builder.bodyBytes(Objects.requireNonNull(original.bodyBytes()));
        }

        try (var hijackedInput = original.hijackedInput()) {
            if (hijackedInput != null) {
                builder.hijackedInput(hijackedInput);
            }
        }

        return builder.build();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private record CachedToken(String token, long expiresAt) {
        boolean isValid() {
            return Instant.now().getEpochSecond() < expiresAt - 30; // 30s buffer
        }
    }

    private static class TokenResponse {
        @JsonProperty("token")
        private String token;

        @JsonProperty("expires_in")
        private int expiresIn = 300; // Default 5 minutes if not specified
    }
}