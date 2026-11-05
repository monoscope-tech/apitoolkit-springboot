package io.apitoolkit.springboot.integrations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import io.apitoolkit.springboot.APErrors;
import io.apitoolkit.springboot.Utils;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;

public class ResponseInterceptor implements HttpResponseInterceptor {

    private HashMap<String, Object> config;
    private String urlPathPattern;
    private List<String> redactHeaders;
    private List<String> redactRequestBody;
    private List<String> redactResponseBody;
    private String parent_id;
    private Boolean debug;

    ResponseInterceptor(HttpServletRequest req, String urlPathPattern, List<String> redactHeaders,
            List<String> redactRequestBody, List<String> redactResponseBody) {
        try {
            config = (HashMap<String, Object>) req.getAttribute("apitoolkit_config");
            this.parent_id = (String) req.getAttribute("apitoolkit_message_id");
            if (config.get("debug") != null && (boolean) config.get("debug")) {
                this.debug = true;
            } else {
                this.debug = false;
            }
            this.urlPathPattern = urlPathPattern;
            this.redactHeaders = redactHeaders;
            this.redactResponseBody = redactResponseBody;
            this.redactRequestBody = redactRequestBody;

        } catch (Exception e) {
            if (config.get("debug") != null && (boolean) config.get("debug")) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void process(HttpResponse response, HttpContext context) {
        try {
            // Taken once, so a failed request that already reached here is not reported twice.
            Span span = (Span) context.removeAttribute("span");
            if (span == null) {
                return;
            }
            String host = (String) context.getAttribute("apitoolkit_host");

            byte[] requestBodyStr = (byte[]) context.getAttribute("apitoolkit_request_body");
            requestBodyStr = requestBodyStr == null ? "".getBytes() : requestBodyStr;
            byte[] requestBody = Utils.redactFields(requestBodyStr, this.redactRequestBody, this.debug);
            HashMap<String, String> requestHeaders = (HashMap<String, String>) context
                    .getAttribute("apitoolkit_request_headers");

            String method = (String) context.getAttribute("apitoolkit_method");
            String rawUrl = (String) context.getAttribute("apitoolkit_raw_url");
            HashMap<String, String> queryParams = (HashMap<String, String>) context
                    .getAttribute("apitoolkit_query_params");

            // response is null when the request failed before one arrived.
            HashMap<String, String> responseHeaders = new HashMap<>();
            int statusCode = 0;
            byte[] responseBody = null;
            if (response != null) {
                for (org.apache.http.Header header : response.getAllHeaders()) {
                    responseHeaders.put(header.getName(), header.getValue());
                }
                statusCode = response.getStatusLine().getStatusCode();
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    responseBody = EntityUtils.toByteArray(entity);
                    response.setEntity(new BufferedHttpEntity(entity, responseBody));
                }
            }

            HashMap<String, String> pathParams = new HashMap<>();
            if (this.urlPathPattern != null && this.urlPathPattern.length() > 0) {
                pathParams = Utils.getPathParamsFromPattern(this.urlPathPattern, rawUrl);
            }

            String urlWithoutQuery = rawUrl.split("\\?")[0];
            String path = this.urlPathPattern != null ? this.urlPathPattern : urlWithoutQuery;

            HashMap<String, Object> outConfig = new HashMap<>();
            outConfig.put("debug", this.debug);
            outConfig.put("redactHeaders", this.redactHeaders);
            outConfig.put("redactRequestBody", this.redactRequestBody);
            outConfig.put("redactResponseBody", this.redactResponseBody);

            List<Map<String, Object>> errors = new ArrayList<>();
            Throwable error = (Throwable) context.removeAttribute("apitoolkit_error");
            if (error != null) {
                errors.add(APErrors.buildError(error));
            }

            Utils.setApitoolkitAttributesAndEndSpan(
                    span,
                    host,
                    statusCode,
                    queryParams,
                    pathParams,
                    requestHeaders,
                    responseHeaders,
                    method,
                    rawUrl,
                    "",
                    path,
                    requestBody,
                    responseBody,
                    errors,
                    outConfig,
                    "JavaApacheOutgoing",
                    this.parent_id
            );
        } catch (Exception e) {
            if (this.debug) {
                e.printStackTrace();
            }
        }
    }

    class BufferedHttpEntity extends HttpEntityWrapper {

        private final byte[] body;

        public BufferedHttpEntity(HttpEntity entity, byte[] body) {
            super(entity);
            this.body = body;
        }

        @Override
        public InputStream getContent() throws IOException {
            return new ByteArrayInputStream(body);
        }

        @Override
        public long getContentLength() {
            return body.length;
        }
    }
}
