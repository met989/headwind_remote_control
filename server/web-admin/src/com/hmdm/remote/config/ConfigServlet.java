package com.hmdm.remote.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration Servlet for Headwind Remote Web-Admin.
 *
 * Provides REST endpoints:
 *   GET  /api/config -> Returns the active remote control configuration (JSON)
 *   POST /api/config -> Saves updated configuration from the Web GUI Settings modal
 *
 * Zero external dependencies: works cleanly on any standard Tomcat 10 / 9 runtime.
 * Access is protected by HmdmAuthFilter (valid Headwind MDM session required).
 */
public class ConfigServlet extends HttpServlet {

    private static final String[] CONFIG_PATHS = new String[] {
            System.getProperty("headwind.remote.config"),
            "/etc/tomcat10/Catalina/headwind-remote.json",
            "/var/lib/tomcat10/conf/headwind-remote.json",
            "/var/lib/tomcat9/conf/headwind-remote.json",
            "/etc/headwind-remote/config.json"
    };

    private static volatile File activeConfigFile = null;

    private File resolveConfigFile() {
        if (activeConfigFile != null && activeConfigFile.exists() && activeConfigFile.canRead()) {
            return activeConfigFile;
        }
        for (String path : CONFIG_PATHS) {
            if (path != null && !path.trim().isEmpty()) {
                File f = new File(path);
                if (f.exists() && f.canRead()) {
                    return f;
                }
            }
        }
        String realPath = getServletContext().getRealPath("/WEB-INF/headwind-remote.json");
        if (realPath != null) {
            File wf = new File(realPath);
            if (wf.exists() && wf.canRead()) return wf;
        }
        File tmp = new File("/tmp/headwind-remote.json");
        if (tmp.exists() && tmp.canRead()) return tmp;

        return new File("/etc/tomcat10/Catalina/headwind-remote.json");
    }

    private String readJanusSecretFallback() {
        File janusCfg = new File("/etc/janus/janus.jcfg");
        if (janusCfg.exists() && janusCfg.canRead()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(janusCfg), StandardCharsets.UTF_8))) {
                String line;
                Pattern p = Pattern.compile("^\\s*api_secret\\s*=\\s*\"([^\"]+)\"");
                while ((line = reader.readLine()) != null) {
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        return m.group(1).trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private Map<String, String> loadConfig() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("portApiHttps", "8089");
        cfg.put("portApiWss", "8989");
        cfg.put("apiSecret", readJanusSecretFallback());
        cfg.put("defaultQuality", "high");
        cfg.put("autoTimeoutMs", "2200");
        cfg.put("maxRetries", "12");
        cfg.put("serverUrl", "");
        cfg.put("stunServer", "stun:stun.l.google.com:19302");
        cfg.put("turnServer", "");
        cfg.put("turnUsername", "");
        cfg.put("turnPassword", "");

        File f = resolveConfigFile();
        if (f.exists() && f.canRead()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String json = sb.toString();
                parseJsonMap(json, cfg);
            } catch (Exception ignored) {}
        }
        return cfg;
    }

    private void saveConfig(Map<String, String> cfg) throws IOException {
        java.util.List<File> candidates = new java.util.ArrayList<>();
        File resolved = resolveConfigFile();
        if (resolved != null) candidates.add(resolved);
        candidates.add(new File("/etc/tomcat10/Catalina/headwind-remote.json"));
        candidates.add(new File("/var/lib/tomcat10/conf/headwind-remote.json"));
        candidates.add(new File("/var/lib/tomcat9/conf/headwind-remote.json"));
        String realPath = getServletContext().getRealPath("/WEB-INF/headwind-remote.json");
        if (realPath != null) candidates.add(new File(realPath));
        candidates.add(new File("/tmp/headwind-remote.json"));

        IOException lastEx = null;
        for (File target : candidates) {
            try {
                writeConfigFile(target, cfg);
                activeConfigFile = target;
                return;
            } catch (IOException e) {
                lastEx = e;
            }
        }
        if (lastEx != null) {
            throw lastEx;
        }
    }

    private void writeConfigFile(File f, Map<String, String> cfg) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // Create temp file in system temp dir to avoid directory permission issues
        File temp = File.createTempFile("hmdm-cfg-", ".tmp");
        try {
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8))) {
                writer.println("{");
                int i = 0;
                int total = cfg.size();
                for (Map.Entry<String, String> entry : cfg.entrySet()) {
                    i++;
                    String k = escapeJson(entry.getKey());
                    String v = escapeJson(entry.getValue());
                    String comma = (i < total) ? "," : "";
                    writer.println("  \"" + k + "\": \"" + v + "\"" + comma);
                }
                writer.println("}");
            }

            // Write in-place to target file (works even if parent dir is not writable)
            try (FileInputStream in = new FileInputStream(temp); FileOutputStream out = new FileOutputStream(f, false)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");

        Map<String, String> cfg = loadConfig();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"OK\",\"data\":{");
        int i = 0;
        int total = cfg.size();
        for (Map.Entry<String, String> entry : cfg.entrySet()) {
            i++;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
              .append(escapeJson(entry.getValue())).append("\"");
            if (i < total) sb.append(",");
        }
        sb.append("}}");

        resp.getWriter().write(sb.toString());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json;charset=UTF-8");

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        String body = sb.toString();
        if (body.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"status\":\"ERROR\",\"message\":\"Empty request payload\"}");
            return;
        }

        Map<String, String> existing = loadConfig();
        parseJsonMap(body, existing);

        try {
            saveConfig(existing);
            resp.getWriter().write("{\"status\":\"OK\",\"message\":\"Configuration saved successfully\"}");
        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("{\"status\":\"ERROR\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void parseJsonMap(String json, Map<String, String> target) {
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+|true|false))");
        Matcher m = p.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            if (key != null && val != null) {
                target.put(key.trim(), val.trim());
            }
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
