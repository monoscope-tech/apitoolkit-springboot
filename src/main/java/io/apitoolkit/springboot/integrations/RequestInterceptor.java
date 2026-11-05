package io.apitoolkit.springboot.integrations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

public class RequestInterceptor implements HttpRequestInterceptor {

    @Override
    public void process(HttpRequest request, HttpContext context) {

        Tracer tracer = GlobalOpenTelemetry.getTracer("");
        Span span = tracer.spanBuilder("apitoolkit-http-span").startSpan();
        context.setAttribute("span", span);

        String method = request.getRequestLine().getMethod();
        String uri = request.getRequestLine().getUri();
        List<NameValuePair> queryParams = null;
        HashMap<String, String> queryParamsMap = new HashMap<>();

        if (uri.contains("?")) {
            String queryString = uri.substring(uri.indexOf("?") + 1);
            queryParams = URLEncodedUtils.parse(queryString, StandardCharsets.UTF_8);
            for (NameValuePair param : queryParams) {
                queryParamsMap.put(param.getName(), param.getValue());
            }

        }

        // The request line is already relative here, so the host has to come from the route target.
        HttpHost target = HttpClientContext.adapt(context).getTargetHost();
        context.setAttribute("apitoolkit_host", target != null ? target.getHostName() : null);

        HashMap<String, String> requestHeaders = new HashMap<>();
        for (org.apache.http.Header header : request.getAllHeaders()) {
            requestHeaders.put(header.getName(), header.getValue());
        }
        String requestBody = "";

        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest entityRequest = (HttpEntityEnclosingRequest) request;
            if (entityRequest.getEntity() != null) {
                try {
                    requestBody = EntityUtils.toString(entityRequest.getEntity(), StandardCharsets.UTF_8);
                    entityRequest.setEntity(new StringEntity(requestBody, ContentType.get(entityRequest.getEntity())));

                } catch (ParseException | IOException e) {

                }
            }
        }
        context.setAttribute("apitoolkit_request_headers", requestHeaders);
        context.setAttribute("apitoolkit_method", method);
        context.setAttribute("apitoolkit_raw_url", uri);
        context.setAttribute("apitoolkit_query_params", queryParamsMap);
        context.setAttribute("apitoolkit_request_body", requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
