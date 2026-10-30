package io.apitoolkit.springboot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class APIToolkitFilter implements Filter {

    @Value("${apitoolkit.debug:false}")
    private Boolean debug;
    @Value("${apitoolkit.redactHeaders:}")
    private String[] redactHeaders;
    @Value("${apitoolkit.redactRequestBody:}")
    private String[] redactRequestBody;
    @Value("${apitoolkit.redactResponseBody:}")
    private String[] redactResponseBody;
    @Value("${apitoolkit.captureRequest_body:false}")
    private Boolean captureRequestBody;
    @Value("${apitoolkit.captureResponse_body:false}")
    private Boolean captureResponseBody;
    @Value("${apitoolkit.serviceName:}")
    private String serviceName;
    @Value("${apitoolkit.serviceVersion:}")
    private String serviceVersion;
    @Value("${apitoolkit.tags:}")
    private String tags;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // We use filterConfig for testing configurations
        // The @Value injection is used to set the values in the application.properties
        // file. The @Value get's inject first, so if apikey is null then we try getting
        // config from filterConfig.
        String[] emptyList = {};

        String rHeaders = filterConfig.getInitParameter("apitoolkit.redactHeaders");
        this.redactHeaders = this.redactHeaders != null ? this.redactHeaders
                : rHeaders != null ? rHeaders.split(",") : emptyList;
        if (this.redactRequestBody == null) {
            String req_body = filterConfig.getInitParameter("apitoolkit.redactRequestBody");
            this.redactRequestBody = req_body != null ? req_body.split(",") : emptyList;
        }

        if (this.redactResponseBody == null) {
            String res_body = filterConfig.getInitParameter("apitoolkit.redactResponseBody");
            this.redactResponseBody = res_body != null ? res_body.split(",") : emptyList;
        }

        if (this.serviceName == null) {
            String serviceN = filterConfig.getInitParameter("apitoolkit.serviceName");
            this.serviceName = serviceN != null ? serviceN : "";
        }
        if (this.serviceVersion == null) {
            this.serviceVersion = filterConfig.getInitParameter("apitoolkit.serviceVersion");
            this.serviceVersion = this.serviceVersion != null ? this.serviceVersion : "";
        }
        if (this.tags == null) {
            this.tags = filterConfig.getInitParameter("apitoolkit.tags");
        }
        if (this.debug == null) {
            this.debug = Boolean.parseBoolean(filterConfig.getInitParameter("apitoolkit.debug"));
        }

        if (this.captureRequestBody == null) {
            this.captureRequestBody = Boolean.parseBoolean(filterConfig.getInitParameter("apitoolkit.captureRequestBody"));
        }
        if (this.captureResponseBody == null) {
            this.captureResponseBody = Boolean.parseBoolean(filterConfig.getInitParameter("apitoolkit.captureResponseBody"));
        }
        if (this.debug == true) {
            System.out.println("Client initialized successfully");
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        Tracer tracer = GlobalOpenTelemetry.getTracer(this.serviceName);
        Span span = tracer.spanBuilder("apitoolkit-http-span").startSpan();

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        List<Map<String, Object>> errors = new ArrayList<>();

        final ContentCachingRequestWrapper requestCache = new ContentCachingRequestWrapper(req);
        final ContentCachingResponseWrapper responseCache = new ContentCachingResponseWrapper(res);
        Integer statusCode = 200;
        String msgId = UUID.randomUUID().toString();

        try {
            requestCache.setAttribute("APITOOLKIT_ERRORS", errors);
            HashMap<String, Object> config = new HashMap<>();
            config.put("debug", this.debug);
            requestCache.setAttribute("apitoolkit_config", config);
            req.setAttribute("apitoolkit_filter", this);
            req.setAttribute("apitoolkit_message_id", msgId);
            chain.doFilter(requestCache, responseCache);
        } catch (Exception e) {
            e.printStackTrace();
            statusCode = 500;
            APErrors.reportError(requestCache, e);
            throw e;
        } finally {
            final byte[] req_body = this.captureRequestBody ? requestCache.getContentAsByteArray() : "".getBytes();
            final byte[] res_body = this.captureResponseBody ? responseCache.getContentAsByteArray() : "".getBytes();
            statusCode = statusCode == 500 ? 500 : responseCache.getStatus();
            responseCache.copyBodyToResponse();
            try {
                buildPayload(span, requestCache, responseCache, req_body, res_body,
                        statusCode, msgId);
            } catch (Exception e) {
                span.end();
                if (this.debug) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void buildPayload(Span span, HttpServletRequest req, HttpServletResponse res,
            byte[] req_body, byte[] res_body, Integer statusCode, String msgid) {
        Enumeration<String> headerNames = req.getHeaderNames();

        HashMap<String, String> reqHeaders = new HashMap<>();

        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = req.getHeader(headerName);
            reqHeaders.put(headerName, headerValue);
        }

        HashMap<String, String> resHeaders = new HashMap<>();
        Collection<String> resHeadersV = res.getHeaderNames();

        for (String headerName : resHeadersV) {
            String headerValue = res.getHeader(headerName);
            resHeaders.put(headerName, headerValue);
        }

        Map<String, String[]> paramsH = req.getParameterMap();
        Map<String, String> params = new HashMap<>();
        for (Map.Entry<String, String[]> entry : paramsH.entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();
            params.put(key, String.join(", ", values));
        }

        String method = req.getMethod();
        String queryString = req.getQueryString() == null ? "" : "?" + req.getQueryString();
        String rawUrl = req.getRequestURI() + queryString;
        String matchedPattern = (String) req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables = (Map<String, String>) req
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errorList = (List<Map<String, Object>>) req.getAttribute("APITOOLKIT_ERRORS");

        Map<String, Object> config = new HashMap<>();
        config.put("serviceVersion", this.serviceVersion);
        config.put("tags", this.tags);
        config.put("debug", this.debug);
        config.put("redactHeaders", Arrays.asList(this.redactHeaders));
        config.put("redactRequestBody", Arrays.asList(this.redactRequestBody));
        config.put("redactResponseBody", Arrays.asList(this.redactResponseBody));

        Utils.setApitoolkitAttributesAndEndSpan(
                span,
                req.getServerName(),
                statusCode,
                params,
                pathVariables,
                reqHeaders,
                resHeaders,
                method,
                rawUrl,
                msgid,
                matchedPattern,
                req_body,
                res_body,
                errorList,
                config,
                "JavaSpringBoot",
                null
        );

    }
}
