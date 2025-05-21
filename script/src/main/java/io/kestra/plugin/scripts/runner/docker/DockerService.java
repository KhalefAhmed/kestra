package io.kestra.plugin.scripts.runner.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.NameParser;
import com.github.dockerjava.transport.DockerHttpClient;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.MapUtils;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DockerService {
    private static final Logger logger = LoggerFactory.getLogger(DockerService.class);

    // Registry URL constants
    public static final String DOCKER_HUB_URL_V1 = "https://index.docker.io/v1/";
    public static final String DOCKER_HUB_URL_V2 = "https://index.docker.io/v2/";

    // Shared registry mapping cache (hostname -> auth URL)
    private static final Map<String, String> REGISTRY_MAPPINGS = new ConcurrentHashMap<>();

    static {
        // Initialize common registry mappings
        REGISTRY_MAPPINGS.put("registry-1.docker.io", DOCKER_HUB_URL_V1);
        REGISTRY_MAPPINGS.put("docker.io", DOCKER_HUB_URL_V1);
        REGISTRY_MAPPINGS.put("index.docker.io", DOCKER_HUB_URL_V1);

        // Add v2 registry mappings
        REGISTRY_MAPPINGS.put("registry-1.docker.io/v2", DOCKER_HUB_URL_V2);
        REGISTRY_MAPPINGS.put("docker.io/v2", DOCKER_HUB_URL_V2);
        REGISTRY_MAPPINGS.put("index.docker.io/v2", DOCKER_HUB_URL_V2);
    }

    public static DockerClient client(DockerClientConfig dockerClientConfig) {
        DockerHttpClient dockerHttpClient = new DockerRegistryAuthHttpClient(dockerClientConfig);

        return DockerClientBuilder
            .getInstance(dockerClientConfig)
            .withDockerHttpClient(dockerHttpClient)
            .build();
    }

    public static String findHost(RunContext runContext, String host) throws IllegalVariableEvaluationException {
        if (host != null) {
            return runContext.render(host);
        }

        if (Files.exists(Path.of("/var/run/docker.sock"))) {
            return "unix:///var/run/docker.sock";
        }

        if (SystemUtils.IS_OS_WINDOWS) {
            return "npipe:////./pipe/docker_engine";
        }

        return "unix:///dind/docker.sock";
    }

    public static DockerClient client(RunContext runContext, @Nullable String host, @Nullable Object config, @Nullable Credentials credentials, @Nullable String image) throws IOException, IllegalVariableEvaluationException {
        DefaultDockerClientConfig.Builder dockerClientConfigBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(DockerService.findHost(runContext, host));

        if (config != null || credentials != null) {
            Path configPath = DockerService.createConfig(
                runContext,
                config,
                credentials != null ? List.of(credentials) : null,
                image
            );

            dockerClientConfigBuilder.withDockerConfig(configPath.toFile().getAbsolutePath());
        }

        DockerClientConfig dockerClientConfig = dockerClientConfigBuilder.build();

        return DockerService.client(dockerClientConfig);
    }

    @SuppressWarnings("unchecked")
    public static Path createConfig(RunContext runContext, @Nullable Object config, @Nullable List<Credentials> credentials, @Nullable String image) throws IllegalVariableEvaluationException, IOException {
        Map<String, Object> finalConfig = new HashMap<>();

        if (config != null) {
            if (config instanceof String configString) {
                finalConfig = JacksonMapper.toMap(runContext.render(configString));
            } else {
                finalConfig = runContext.render((Map<String, Object>) config);
            }
        }

        if (credentials != null) {
            Map<String, Map<String, Object>> authsMap = new HashMap<>();

            for (Credentials c : credentials) {
                Map<String, Object> auths = new HashMap<>();

                if (c.getUsername() != null) {
                    auths.put("username", runContext.render(c.getUsername()).as(String.class).orElse(null));
                }

                if (c.getPassword() != null) {
                    auths.put("password", runContext.render(c.getPassword()).as(String.class).orElse(null));
                }

                if (c.getRegistryToken() != null) {
                    auths.put("registrytoken", runContext.render(c.getRegistryToken()).as(String.class).orElse(null));
                }

                if (c.getIdentityToken() != null) {
                    auths.put("identitytoken", runContext.render(c.getIdentityToken()).as(String.class).orElse(null));
                }

                if (c.getAuth() != null) {
                    auths.put("auth", runContext.render(c.getAuth()).as(String.class).orElse(null));
                }

                // Determine registry URL(s)
                String registry = DOCKER_HUB_URL_V1; // Default to Docker Hub v1

                if (c.getRegistry() != null) {
                    String configuredRegistry = runContext.render(c.getRegistry()).as(String.class).orElse(null);

                    if (configuredRegistry != null) {
                        registry = configuredRegistry;

                        // If using Docker Hub with v2 API, add both entries
                        if (isDockerHubRegistry(configuredRegistry)) {
                            // Add auth to standard V1 URL (for compatibility)
                            authsMap.put(DOCKER_HUB_URL_V1, auths);

                            // If specifically using V2 URL, add that too
                            if (configuredRegistry.contains("/v2")) {
                                authsMap.put(DOCKER_HUB_URL_V2, auths);

                                if (logger.isDebugEnabled()) {
                                    logger.debug("Added Docker Hub V2 auth config");
                                }
                            }
                        }
                    }
                } else if (image != null) {
                    String renderedImage = runContext.render(image);
                    String detectedRegistry = registryUrlFromImage(renderedImage);

                    if (detectedRegistry != null && !detectedRegistry.equals(DOCKER_HUB_URL_V1)) {
                        registry = detectedRegistry;

                        if (logger.isDebugEnabled()) {
                            logger.debug("Detected registry from image: {}", registry);
                        }
                    }
                }

                // Add registry entry if not already added
                if (!authsMap.containsKey(registry)) {
                    authsMap.put(registry, auths);
                }
            }

            finalConfig = MapUtils.merge(finalConfig, Map.of("auths", authsMap));
        }

        File docker = runContext.workingDir().path(true).resolve("config.json").toFile();

        if (docker.exists()) {
            //noinspection ResultOfMethodCallIgnored
            docker.delete();
        } else {
            Files.createFile(docker.toPath());
        }

        Files.write(
            docker.toPath(),
            runContext.render(JacksonMapper.ofJson().writeValueAsString(finalConfig)).getBytes()
        );

        if (logger.isDebugEnabled()) {
            logger.debug("Created Docker config at: {}", docker.getPath());
        }

        return docker.toPath().getParent();
    }

    /**
     * Convert image name to registry URL in a format expected by Docker authentication.
     */
    public static String registryUrlFromImage(String image) {
        try {
            NameParser.ReposTag imageParse = NameParser.parseRepositoryTag(image);
            String host = extractHostFromRepos(imageParse.repos);

            return getRegistryUrl(host);
        } catch (Exception e) {
            logger.warn("Failed to parse registry from image: {}", image, e);
            return DOCKER_HUB_URL_V1;
        }
    }

    /**
     * Get the registry URL for a given hostname
     */
    public static String getRegistryUrl(String hostname) {
        if (hostname == null) {
            return DOCKER_HUB_URL_V1;
        }

        // Check for v2 endpoint in URL
        if (hostname.contains("/v2")) {
            return DOCKER_HUB_URL_V2;
        }

        // Support for full URLs containing version
        if (hostname.startsWith("http") && hostname.contains("/v2/")) {
            return hostname;
        }

        // Check cache first
        if (REGISTRY_MAPPINGS.containsKey(hostname)) {
            return REGISTRY_MAPPINGS.get(hostname);
        }

        // For other registries, create a proper URL
        String url = hostname.startsWith("http") ? hostname : "https://" + hostname;

        // Cache the mapping for future use
        REGISTRY_MAPPINGS.put(hostname, url);
        return url;
    }

    /**
     * Check if this is a Docker Hub registry
     */
    public static boolean isDockerHubRegistry(String url) {
        return url != null && (url.contains("docker.io") || url.contains("index.docker.io"));
    }

    private static String extractHostFromRepos(String repos) {
        if (repos.equals("docker.io") || repos.contains("index.docker.io")) {
            return "docker.io";
        }
        try {
            URI uri = URI.create(repos.startsWith("http") ? repos : "https://" + repos);
            return uri.getHost();
        } catch (Exception e) {
            return repos;
        }
    }
}