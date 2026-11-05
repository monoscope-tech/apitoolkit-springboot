package io.apitoolkit.springboot.integrations;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;

import io.opentelemetry.api.trace.Span;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.List;

public class ObserveRequest {
    private List<String> redactHeaders;
    private List<String> redactRequestBody;
    private List<String> redactResponseBody;

    // Primary constructor
    public ObserveRequest(List<String> redactHeaders,
            List<String> redactRequestBody,
            List<String> redactResponseBody) {
        this.redactHeaders = redactHeaders;
        this.redactRequestBody = redactRequestBody;
        this.redactResponseBody = redactResponseBody;
    }

    public ObserveRequest() {
        this(null, null, null);
    }

    public ObserveRequest(List<String> redactHeaders) {
        this(redactHeaders, null, null);
    }

    public ObserveRequest(List<String> redactHeaders,
            List<String> redactRequestBody) {
        this(redactHeaders, redactRequestBody, null);
    }

    public CloseableHttpClient createHttpClient(HttpServletRequest request, String urlPathPattern) {
        final ResponseInterceptor responseInterceptor = new ResponseInterceptor(request, urlPathPattern,
                this.redactHeaders, this.redactRequestBody, this.redactResponseBody);
        final CloseableHttpClient client = HttpClients.custom()
                .addInterceptorFirst(new RequestInterceptor())
                .addInterceptorFirst(responseInterceptor)
                .build();
        // Response interceptors never run when a request fails (refused, timed out, reset), so the
        // span the request interceptor started is ended here with the request details it captured.
        return new CloseableHttpClient() {
            @Override
            protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest req, HttpContext context)
                    throws IOException {
                HttpContext ctx = context != null ? context : new BasicHttpContext();
                try {
                    return client.execute(target, req, ctx);
                } catch (IOException | RuntimeException e) {
                    Span span = (Span) ctx.getAttribute("span");
                    if (span != null) {
                        span.recordException(e);
                    }
                    responseInterceptor.process(null, ctx);
                    throw e;
                }
            }

            @Override
            public void close() throws IOException {
                client.close();
            }

            @Override
            @SuppressWarnings("deprecation")
            public org.apache.http.params.HttpParams getParams() {
                return client.getParams();
            }

            @Override
            @SuppressWarnings("deprecation")
            public org.apache.http.conn.ClientConnectionManager getConnectionManager() {
                return client.getConnectionManager();
            }
        };
    }

    public CloseableHttpClient createHttpClient(HttpServletRequest request) {
        return createHttpClient(request, null);
    }
}
