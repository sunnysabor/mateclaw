package vip.mate.dify.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.exception.MateClawException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DifyWorkflowClient {

    public static final String BASE_URL = "https://api.dify.ai/v1";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(95);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public RunResponse run(String apiKey, Map<String, Object> inputs, String difyUser) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputs", inputs == null ? Map.of() : inputs);
        payload.put("response_mode", "blocking");
        payload.put("user", difyUser);
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new MateClawException("err.dify.input_invalid", 400,
                    "Dify inputs must be a JSON object");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/workflows/run"))
                .timeout(READ_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw toDifyException(response.statusCode(), response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return new RunResponse(root, response.body());
        } catch (MateClawException e) {
            throw e;
        } catch (DifyClientException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new DifyClientException("timeout", "Dify workflow request timed out", null, e);
        } catch (IOException e) {
            throw new DifyClientException("network_error", "Dify workflow network error", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DifyClientException("interrupted", "Dify workflow request interrupted", null, e);
        } catch (Exception e) {
            throw new DifyClientException("dify_parse_error", "Failed to parse Dify workflow response", null, e);
        }
    }

    private DifyClientException toDifyException(int status, String body) {
        String code = "http_" + status;
        String message = "Dify workflow request failed";
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            if (root.hasNonNull("code")) code = root.get("code").asText();
            if (root.hasNonNull("message")) message = root.get("message").asText();
            else if (root.hasNonNull("error")) message = root.get("error").asText();
        } catch (Exception ignored) {
            if (body != null && !body.isBlank()) {
                message = body.length() > 512 ? body.substring(0, 512) : body;
            }
        }
        return new DifyClientException(code, message, body, null);
    }

    public record RunResponse(JsonNode root, String rawJson) {
    }

    public static class DifyClientException extends RuntimeException {
        private final String errorCode;
        private final String rawResponse;

        public DifyClientException(String errorCode, String message, String rawResponse, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
            this.rawResponse = rawResponse;
        }

        public String errorCode() {
            return errorCode;
        }

        public String rawResponse() {
            return rawResponse;
        }
    }
}
