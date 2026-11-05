package io.apitoolkit.springboot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import io.opentelemetry.api.trace.Span;

public class Utils {

    private static final Gson GSON = new Gson();
    private static final List<String> DEFAULT_REDACTED_HEADERS = Arrays.asList(
            "authorization", "cookie", "set-cookie", "proxy-authorization");

    public static void setApitoolkitAttributesAndEndSpan(
            Span span,
            String host,
            int statusCode,
            Map<String, String> queryParams,
            Map<String, String> pathParams,
            Map<String, String> reqHeaders,
            Map<String, String> respHeaders,
            String method,
            String rawUrl,
            String msgId,
            String urlPath,
            byte[] reqBody,
            byte[] respBody,
            List<Map<String, Object>> errors,
            Map<String, Object> config,
            String sdkType,
            String parentId) {
        try {
            List<String> emptyList = new ArrayList<>();
            List<String> redactRequestBody = (List<String>) config.getOrDefault("redactRequestBody", emptyList);
            List<String> redactResponseBody = (List<String>) config.getOrDefault("redactResponseBody", emptyList);
            List<String> redactHeaders = (List<String>) config.getOrDefault("redactHeaders", emptyList);
            if (redactRequestBody == null) {
                redactRequestBody = emptyList;
            }
            if (redactResponseBody == null) {
                redactResponseBody = emptyList;
            }
            if (redactHeaders == null) {
                redactHeaders = emptyList;
            }
            Boolean debug = (Boolean) config.getOrDefault("debug", false);
            String encodedRequestBody = Base64.getEncoder()
                    .encodeToString(redactFields(reqBody, redactRequestBody, debug));
            String encodedResponseBody = Base64.getEncoder()
                    .encodeToString(redactFields(respBody, redactResponseBody, debug));
            span.setAttribute("net.host.name", host);
            span.setAttribute("apitoolkit.msg_id", msgId);
            span.setAttribute("http.route", urlPath);
            span.setAttribute("http.target", rawUrl);
            span.setAttribute("http.request.method", method);
            span.setAttribute("http.response.status_code", statusCode);
            span.setAttribute("http.request.query_params", GSON.toJson(queryParams));
            span.setAttribute("http.request.path_params", GSON.toJson(pathParams));
            span.setAttribute("apitoolkit.sdk_type", sdkType);
            span.setAttribute("apitoolkit.parent_id", Optional.ofNullable(parentId).orElse(""));
            span.setAttribute("http.request.body", encodedRequestBody);
            span.setAttribute("http.response.body", encodedResponseBody);
            span.setAttribute("apitoolkit.errors", GSON.toJson(errors));
            span.setAttribute("apitoolkit.service_version", (String) config.getOrDefault("serviceVersion", ""));
            span.setAttribute("apitoolkit.tags", GSON.toJson(config.getOrDefault("tags", emptyList)));

            for (Map.Entry<String, String> header : reqHeaders.entrySet()) {
                span.setAttribute(
                        "http.request.header." + header.getKey(),
                        redactHeader(header.getKey(), header.getValue(), redactHeaders));
            }

            for (Map.Entry<String, String> header : respHeaders.entrySet()) {
                span.setAttribute(
                        "http.response.header." + header.getKey(),
                        redactHeader(header.getKey(), header.getValue(), redactHeaders));
            }
        } catch (Exception error) {
            error.printStackTrace();
            span.recordException(error);
        } finally {
            span.end();
        }
    }

    public static String redactHeader(String header, String headerVal, List<String> redactHeaders) {
        List<String> names = new ArrayList<>(DEFAULT_REDACTED_HEADERS);
        if (redactHeaders != null) {
            names.addAll(redactHeaders);
        }
        for (String name : names) {
            if (name.trim().equalsIgnoreCase(header)) {
                return "[CLIENT_REDACTED]";
            }
        }
        return headerVal;
    }

    public static byte[] redactFields(byte[] data, List<String> jsonPaths, Boolean debug) {
        if (data == null) {
            return new byte[0];
        }
        if (jsonPaths == null || jsonPaths.isEmpty() || data.length == 0) {
            return data;
        }
        try {

            String jsonData = new String(data, StandardCharsets.UTF_8);
            DocumentContext jsonObject = JsonPath.parse(jsonData);
            for (String path : jsonPaths) {
                try {
                    jsonObject = jsonObject.set(path, "[CLIENT_REDACTED]");
                } catch (Exception e) {
                    if (debug) {
                        e.printStackTrace();
                    }
                }
            }
            String redactedJson = jsonObject.jsonString();
            return redactedJson.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (debug) {
                e.printStackTrace();
            }
            return data;
        }

    }

    public static HashMap<String, String> getPathParamsFromPattern(String pattern, String path) {
        HashMap<String, String> pathParams = new HashMap<>();
        String[] pathParts = path.split("/");
        for (int i = 0; i < pathParts.length; i++) {
            String pathPart = pathParts[i];
            String patternPart = pattern.split("/")[i];
            if (patternPart.startsWith("{") && patternPart.endsWith("}")) {
                String paramName = patternPart.substring(1, patternPart.length() - 1);
                pathParams.put(paramName, pathPart);
            }
        }
        return pathParams;
    }
}
