package io.apitoolkit.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
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
        // AttributeKey.stringKey is a static interface method, which -source 7 rejects.
        for (Map.Entry<AttributeKey<?>, Object> e : span.getAttributes().asMap().entrySet()) {
            if (e.getKey().getKey().equals(key)) {
                return String.valueOf(e.getValue());
            }
        }
        return null;
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
            HttpGet get = new HttpGet("http://127.0.0.1:" + server.getAddress().getPort() + "/items?id=1");
            get.addHeader("Authorization", "Bearer abc");
            get.addHeader("x-api-key", "k");
            client.execute(get).close();
        } finally {
            server.stop(0);
        }

        SpanData span = onlySpan();
        assertEquals("127.0.0.1", attr(span, "net.host.name"));
        assertEquals("GET", attr(span, "http.request.method"));
        assertEquals("/items?id=1", attr(span, "http.target"));
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
    }
}
