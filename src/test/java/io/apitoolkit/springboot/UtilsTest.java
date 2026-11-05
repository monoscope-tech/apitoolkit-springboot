package io.apitoolkit.springboot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;

public class UtilsTest {

    @Test
    public void testRedactHeaders() {
        List<String> redactedHeaders = Arrays.asList("X-Api-Key", " x-session");
        assertEquals("[CLIENT_REDACTED]", Utils.redactHeader("X-Api-Key", "key", redactedHeaders));
        assertEquals("[CLIENT_REDACTED]", Utils.redactHeader("x-api-key", "key", redactedHeaders));
        assertEquals("[CLIENT_REDACTED]", Utils.redactHeader("X-Session", "sid", redactedHeaders));
        assertEquals("application/json", Utils.redactHeader("Content-Type", "application/json", redactedHeaders));
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
    public void testRedactsCredentialHeadersByDefault() {
        for (List<String> configured : Arrays.asList(Arrays.<String>asList(), null)) {
            for (String header : Arrays.asList("Authorization", "cookie", "Set-Cookie", "PROXY-AUTHORIZATION")) {
                assertEquals("[CLIENT_REDACTED]", Utils.redactHeader(header, "secret", configured));
            }
            assertEquals("application/json", Utils.redactHeader("Content-Type", "application/json", configured));
        }
    }

    @Test
    public void testKeepsRequestDetailsWhenResponseHasNoBody() {
        Span span = mock(Span.class);
        HashMap<String, String> reqHeaders = new HashMap<>();
        reqHeaders.put("Authorization", "Bearer abc");
        Utils.setApitoolkitAttributesAndEndSpan(span, "example.com", 204, new HashMap<String, String>(),
                new HashMap<String, String>(), reqHeaders, new HashMap<String, String>(), "DELETE", "/items/1",
                "", "/items/{id}", "{}".getBytes(StandardCharsets.UTF_8), null,
                new ArrayList<Map<String, Object>>(), new HashMap<String, Object>(), "JavaApacheOutgoing", null);
        verify(span).setAttribute("net.host.name", "example.com");
        verify(span).setAttribute("http.request.method", "DELETE");
        verify(span).setAttribute("http.response.body", "");
        verify(span).setAttribute("http.request.header.Authorization", "[CLIENT_REDACTED]");
        verify(span).end();
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
