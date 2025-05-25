package io.kestra.plugin.scripts.runner.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.transport.DockerHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class RegistryAuthAwareDockerHttpClient implements DockerHttpClient {

    private final DockerHttpClient delegate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final Map<String, String> tokenCache = new HashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryAuthAwareDockerHttpClient.class);

    public RegistryAuthAwareDockerHttpClient(DockerHttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response execute(Request request) {
        Response response = delegate.execute(request);
        LOGGER.info("Executing request: {} {}", request.method(), request.path());
        LOGGER.info("Response status: {}", response.getStatusCode());
        LOGGER.info("Response headers: {}", response.getHeaders());

        if (response.getStatusCode() == 401) {
            String wwwAuthenticate = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
            if (wwwAuthenticate != null) {
                try {
                    response.close();

                    Map<String, String> authParams = parseAuthHeader(wwwAuthenticate);
                    String realm = authParams.get("realm");
                    String service = authParams.get("service");
                    String scope = authParams.get("scope");

                    String token = getToken(realm, service, scope);

                    Request.Builder newRequestBuilder = Request.builder()
                        .method(request.method())
                        .path(request.path());

                    for (Map.Entry<String, String> header : request.headers().entrySet()) {
                        newRequestBuilder.putHeader(header.getKey(), header.getValue());
                    }

                    // Add authorization header
                    newRequestBuilder.putHeader("Authorization", "Bearer " + token);

                    if (request.bodyBytes() != null) {
                        newRequestBuilder.bodyBytes(request.bodyBytes());
                    }

                    if (request.hijackedInput() != null) {
                        newRequestBuilder.hijackedInput(request.hijackedInput());
                    }

                    return delegate.execute(newRequestBuilder.build());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to handle registry authentication", e);
                }
            }
        }

        return response;
    }

    private Map<String, String> parseAuthHeader(String header) {
        Map<String, String> params = new HashMap<>();
        Pattern pattern = Pattern.compile("([a-zA-Z0-9]+)=\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(header);

        while (matcher.find()) {
            params.put(matcher.group(1), matcher.group(2));
        }

        return params;
    }

    private String getToken(String realm, String service, String scope) throws IOException, ParseException {
        String cacheKey = realm + "|" + service + "|" + scope;
        if (tokenCache.containsKey(cacheKey)) {
            return tokenCache.get(cacheKey);
        }

        StringBuilder urlBuilder = new StringBuilder(realm);
        urlBuilder.append("?service=").append(URLEncoder.encode(service, StandardCharsets.UTF_8));

        if (scope != null) {
            urlBuilder.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        }

        HttpGet httpGet = new HttpGet(urlBuilder.toString());
        String responseBody = EntityUtils.toString(httpClient.execute(httpGet).getEntity());

        JsonNode jsonNode = objectMapper.readTree(responseBody);
        String token = jsonNode.get("token").asText();

        tokenCache.put(cacheKey, token);

        return token;
    }

    @Override
    public void close() throws IOException {
        try {
            httpClient.close();
        } finally {
            delegate.close();
        }
    }
}