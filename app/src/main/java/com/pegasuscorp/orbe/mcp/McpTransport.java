package com.pegasuscorp.orbe.mcp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Transport HTTP du client MCP (Streamable HTTP) — injectable pour les tests. */
public interface McpTransport {

    final class Response {
        public final int status;
        public final String contentType;
        public final String body;
        public final String sessionId;

        public Response(int status, String contentType, String body, String sessionId) {
            this.status = status;
            this.contentType = contentType != null ? contentType : "";
            this.body = body != null ? body : "";
            this.sessionId = sessionId;
        }
    }

    Response post(String url, Map<String, String> headers, String body) throws Exception;

    /** Implémentation réelle : HttpURLConnection, corps borné. */
    final class Http implements McpTransport {
        private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

        @Override
        public Response post(String url, Map<String, String> headers, String body)
                throws Exception {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", "Orbe-Pegase/1.0");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = "";
            if (in != null) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] buf = new char[4096];
                    StringBuilder sb = new StringBuilder();
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        sb.append(buf, 0, n);
                        if (sb.length() > MAX_BODY_BYTES) break;
                    }
                    text = sb.toString();
                }
            }
            Response resp = new Response(code, conn.getContentType(), text,
                    conn.getHeaderField("Mcp-Session-Id"));
            conn.disconnect();
            return resp;
        }
    }
}
