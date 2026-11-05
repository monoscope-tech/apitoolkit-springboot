package io.apitoolkit.springboot;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class UtilsTest {

    @Test
    public void testRedactHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("Content-Type", "application/json");
        HashMap<String, String> result = new HashMap<>();
        List<String> redactedHeaders = Arrays.asList("Authorization");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            result.put(header.getKey(), Utils.redactHeader(header.getKey(), header.getValue(), redactedHeaders));
        }
        assertEquals("[CLIENT_REDACTED]", result.get("Authorization"));
        assertEquals("application/json", result.get("Content-Type"));
    }

    @Test
    public void testRedactJson() {
        String json = "{\"name\": \"John Doe\", \"password\": \"123456\", \"email\": \"john.doe@example.com\"}";
        byte[] jsonData = json.getBytes(StandardCharsets.UTF_8);

        List<String> jsonPaths = Arrays.asList("$.password");

        byte[] result = Utils.redactFields(jsonData, jsonPaths, false);
        String resultJson = new String(result, StandardCharsets.UTF_8);

        assertTrue(resultJson.contains("\"password\":\"[CLIENT_REDACTED]\""));
        assertTrue(resultJson.contains("\"name\":\"John Doe\""));
        assertTrue(resultJson.contains("\"email\":\"john.doe@example.com\""));
    }

    @Test
    public void testGetPathParamsFromPattern() {
        String pattern = "/users/{userId}/orders/{orderId}";
        String path = "/users/123/orders/456";

        HashMap<String, String> result = Utils.getPathParamsFromPattern(pattern, path);

        assertEquals("123", result.get("userId"));
        assertEquals("456", result.get("orderId"));
    }

    @Test
    public void testRedactHeadersEmptyRedactedHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("Content-Type", "application/json");

        List<String> redactedHeaders = Arrays.asList();

        HashMap<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            result.put(header.getKey(), Utils.redactHeader(header.getKey(), header.getValue(), redactedHeaders));
        }

        assertEquals("Bearer token", result.get("Authorization"));
        assertEquals("application/json", result.get("Content-Type"));
    }

    @Test
    public void testRedactJsonEmptyData() {
        byte[] jsonData = new byte[0];
        List<String> jsonPaths = Arrays.asList("$.password");

        byte[] result = Utils.redactFields(jsonData, jsonPaths, false);

        assertEquals(0, result.length);
    }

    @Test
    public void testRedactJsonNullJsonPaths() {
        String json = "{\"name\": \"John Doe\", \"password\": \"123456\", \"email\": \"john.doe@example.com\"}";
        byte[] jsonData = json.getBytes(StandardCharsets.UTF_8);

        List<String> jsonPaths = null;

        byte[] result = Utils.redactFields(jsonData, jsonPaths, false);
        String resultJson = new String(result, StandardCharsets.UTF_8);

        assertEquals(json, resultJson);
    }

    @Test
    public void testGetPathParamsFromPatternNoParams() {
        String pattern = "/users/all";
        String path = "/users/all";

        HashMap<String, String> result = Utils.getPathParamsFromPattern(pattern, path);

        assertTrue(result.isEmpty());
    }
}
