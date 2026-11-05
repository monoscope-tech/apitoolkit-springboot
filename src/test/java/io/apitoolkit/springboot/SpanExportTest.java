package io.apitoolkit.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.apitoolkit.springboot.integrations.ObserveRequest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.http.HttpServletResponse;

// Asserts on the exported span, which is what reaches the backend.
public class SpanExportTest {

    private static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();
    private static final String REDACTED = "[CLIENT_REDACTED]";

    static {
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER)).build())
                .build());
    }

    @BeforeEach
    public void reset() {
        EXPORTER.reset();
    }

    private static String attr(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    private static SpanData onlySpan() {
        List<SpanData> spans = EXPORTER.getFinishedSpanItems();
        assertEquals(1, spans.size());
        return spans.get(0);
    }

    @Test
    public void testFilterRedactsCredentialHeaders() throws Exception {
        HashMap<String, String> conf = new HashMap<>();
        conf.put("apitoolkit.redactHeaders", "X-Api-Key");
        standaloneSetup(new TestController())
                .addFilter(new APIToolkitFilter(), "APIToolkitFilter", conf, null, "*")
                .build()
                .perform(get("/login").header("Authorization", "Bearer abc").header("Cookie", "sid=1")
                        .header("x-api-key", "k").header("Accept", "text/plain"));

        SpanData span = onlySpan();
        assertEquals(REDACTED, attr(span, "http.request.header.Authorization"));
        assertEquals(REDACTED, attr(span, "http.request.header.Cookie"));
        assertEquals(REDACTED, attr(span, "http.request.header.x-api-key"));
        assertEquals("text/plain", attr(span, "http.request.header.Accept"));
        assertEquals(REDACTED, attr(span, "http.response.header.Set-Cookie"));
        assertEquals("ok", attr(span, "http.response.header.X-Result"));
    }

    @Test
    public void testFilterRedactsQueryParams() throws Exception {
        HashMap<String, String> conf = new HashMap<>();
        conf.put("apitoolkit.redactHeaders", "X-Session");
        standaloneSetup(new TestController())
                .addFilter(new APIToolkitFilter(), "APIToolkitFilter", conf, null, "*")
                .build()
                .perform(get("/login?API_KEY=k&page=1&x-session=s&sig"));

        SpanData span = onlySpan();
        assertEquals("/login?API_KEY=%5BCLIENT_REDACTED%5D&page=1&x-session=%5BCLIENT_REDACTED%5D&sig",
                attr(span, "http.target"));
        String params = attr(span, "http.request.query_params");
        assertTrue(params.contains("\"API_KEY\":\"[CLIENT_REDACTED]\""), params);
        assertTrue(params.contains("\"x-session\":\"[CLIENT_REDACTED]\""), params);
        assertTrue(params.contains("\"page\":\"1\""), params);
    }

    // Set the way a Spring app does, through properties, not filter init params.
    @ParameterizedTest
    @ValueSource(strings = { "captureRequestBody", "captureRequest_body" })
    public void testCaptureBodyFromSpringProperties(String requestProp) {
        String responseProp = requestProp.replace("Request", "Response");
        new ApplicationContextRunner()
                .withPropertyValues("apitoolkit." + requestProp + "=true", "apitoolkit." + responseProp + "=true")
                .withBean(APIToolkitFilter.class)
                .run(ctx -> {
                    standaloneSetup(new TestController())
                            .addFilter(ctx.getBean(APIToolkitFilter.class), "APIToolkitFilter",
                                    new HashMap<String, String>(), null, "*")
                            .build()
                            .perform(post("/echo").content("hello"));
                    SpanData span = onlySpan();
                    String body = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
                    assertEquals(body, attr(span, "http.request.body"));
                    assertEquals(body, attr(span, "http.response.body"));
                });
    }

    @Test
    public void testOutgoingRequestKeepsDetailsAndRedactsHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().add("Set-Cookie", "sid=2");
                ex.sendResponseHeaders(204, -1);
                ex.close();
            }
        });
        server.start();
        try (CloseableHttpClient client = new ObserveRequest(Arrays.asList("X-Api-Key")).createHttpClient(parent())) {
            HttpGet get = new HttpGet("http://127.0.0.1:" + server.getAddress().getPort() + "/items?id=1&token=t");
            get.addHeader("Authorization", "Bearer abc");
            get.addHeader("x-api-key", "k");
            client.execute(get).close();
        } finally {
            server.stop(0);
        }

        SpanData span = onlySpan();
        assertEquals("127.0.0.1", attr(span, "net.host.name"));
        assertEquals("GET", attr(span, "http.request.method"));
        assertEquals("/items?id=1&token=%5BCLIENT_REDACTED%5D", attr(span, "http.target"));
        String params = attr(span, "http.request.query_params");
        assertTrue(params.contains("\"token\":\"[CLIENT_REDACTED]\""), params);
        assertTrue(params.contains("\"id\":\"1\""), params);
        assertEquals(REDACTED, attr(span, "http.request.header.Authorization"));
        assertEquals(REDACTED, attr(span, "http.request.header.x-api-key"));
        // The JDK server sends the name as "Set-cookie".
        assertEquals(REDACTED, attr(span, "http.response.header.Set-cookie"));
    }

    @Test
    public void testOutgoingRequestIsExportedWhenConnectionFails() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        final HttpGet get = new HttpGet("http://127.0.0.1:" + port + "/items");
        get.addHeader("Authorization", "Bearer abc");
        try (final CloseableHttpClient client = new ObserveRequest().createHttpClient(parent())) {
            assertThrows(IOException.class, new org.junit.jupiter.api.function.Executable() {
                public void execute() throws Throwable {
                    client.execute(get);
                }
            });
        }

        SpanData span = onlySpan();
        assertEquals("127.0.0.1", attr(span, "net.host.name"));
        assertEquals("GET", attr(span, "http.request.method"));
        assertEquals("/items", attr(span, "http.target"));
        assertEquals(REDACTED, attr(span, "http.request.header.Authorization"));
        String errors = attr(span, "apitoolkit.errors");
        assertTrue(errors.contains("java.net.ConnectException"), errors);
    }

    private static MockHttpServletRequest parent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("apitoolkit_config", new HashMap<String, Object>());
        req.setAttribute("apitoolkit_message_id", "parent-id");
        return req;
    }

    @Controller
    private static class TestController {
        @GetMapping("/login")
        @ResponseBody
        public String login(HttpServletResponse response) {
            response.addHeader("Set-Cookie", "sid=2");
            response.addHeader("X-Result", "ok");
            return "ok";
        }

        @PostMapping("/echo")
        @ResponseBody
        public String echo(@RequestBody String body) {
            return body;
        }
    }
}
