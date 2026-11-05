package io.apitoolkit.springboot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Controller;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class APIToolkitFilterTest {

    private HashMap<String, String> filterConfig;

    private APIToolkitFilter apiToolkitFilter;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    public void setup() {
        HashMap<String, String> fConf = new HashMap<>();
        fConf.put("apitoolkit.debug", "false");
        fConf.put("apitoolkit.redactHeaders", "X-Api-Key");
        fConf.put("apitoolkit.redactRequestBody", "$.password,$.email");
        fConf.put("apitoolkit.redactResponseBody", "$.password,$.email");
        fConf.put("apitoolkit.captureRequestBody", "true");
        fConf.put("apitoolkit.captureResponseBody", "true");
        this.filterConfig = fConf;
    }

    @Test
    public void testDoFilter() throws IOException, ServletException, Exception {
        apiToolkitFilter = new APIToolkitFilter();
        standaloneSetup(new TestController())
                .addFilter(apiToolkitFilter, "APIToolkitFilter", this.filterConfig, null, "*")
                .build()
                .perform(get("/java-test"))
                .andExpectAll(status().isOk(), header().exists("x-api"), header().string("authorization", "broo"));
    }

    @Test
    public void testPostRequestWithBody() throws Exception {
        apiToolkitFilter = new APIToolkitFilter();
        String jsonRequestBody = "{\"username\": \"user\", \"password\": \"pass\"}";

        standaloneSetup(new TestController())
                .addFilter(apiToolkitFilter, "APIToolkitFilter", this.filterConfig, null, "*")
                .build()
                .perform(post("/post-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequestBody))
                .andExpectAll(status().isOk(), header().exists("x-api"), content().string("post received"));
    }

    @Test
    public void testPostRequestWithFileResponse() throws Exception {
        apiToolkitFilter = new APIToolkitFilter();

        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain",
                "test content".getBytes(StandardCharsets.UTF_8));

        standaloneSetup(new TestController())
                .addFilter(apiToolkitFilter, "APIToolkitFilter", this.filterConfig, null, "*")
                .build()
                .perform(multipart("/file-upload-test")
                        .file(file))
                .andExpectAll(status().isOk(), header().exists("x-api"), content().string("file response content"));
    }

    @Controller
    private static class TestController {

        @GetMapping("/java-test")
        public String get(HttpServletRequest request, HttpServletResponse response) {
            assertEquals("GET", request.getMethod());
            response.addHeader("x-api", "got it");
            response.addHeader("authorization", "broo");
            return "got it";
        }

        @PostMapping("/post-test")
        @ResponseBody
        public String post(@RequestBody HashMap<String, String> body, HttpServletResponse response) {
            assertEquals("user", body.get("username"));
            assertEquals("pass", body.get("password"));
            response.addHeader("x-api", "post received");
            return "post received";
        }

        @PostMapping("/file-upload-test")
        public void handleFileUpload(@RequestParam("file") MockMultipartFile file, HttpServletResponse response)
                throws IOException {
            assertEquals("test.txt", file.getOriginalFilename());
            assertEquals("text/plain", file.getContentType());
            assertEquals("test content", new String(file.getBytes(), StandardCharsets.UTF_8));
            response.addHeader("x-api", "file uploaded");
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"response.txt\"");
            response.getOutputStream().write("file response content".getBytes(StandardCharsets.UTF_8));
        }
    }
}
