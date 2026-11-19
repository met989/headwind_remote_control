package com.hmdm.remote.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single Sign-On (SSO) Servlet Filter for Headwind Remote Web-Admin.
 *
 * Validates the caller's Headwind MDM JSESSIONID cookie internally against
 * http://127.0.0.1:8080/rest/private/users/current and ensures the user has
 * sufficient permissions (plugin_push_send, edit_devices, or superadmin).
 *
 * Exempts only /remote-hook.js (contains no secrets) so the Headwind MDM
 * web panel can load the Devices 3-dots menu hook before or during login.
 */
public class HmdmAuthFilter implements Filter {

    private static final long CACHE_TTL_MS = 10_000L;
    private static final ConcurrentHashMap<String, Long> VALID_SESSIONS = new ConcurrentHashMap<>();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String contextPath = req.getContextPath();
        String uri = req.getRequestURI();
        String path = uri.substring(contextPath.length());

        // Allow public UI hook script (contains no secrets) so MDM panel loads cleanly
        if ("/remote-hook.js".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        String cookieHeader = req.getHeader("Cookie");
        String jsessionId = extractJSessionId(cookieHeader);

        if (jsessionId != null && isAuthorizedHmdmSession(jsessionId, cookieHeader)) {
            chain.doFilter(request, response);
            return;
        }

        // Not authenticated in Headwind MDM: redirect browser page visits to MDM login, block assets with 403
        boolean isHtmlPage = "/".equals(path) || "".equals(path) || "/index.html".equals(path) || "/settings.html".equals(path);
        if (isHtmlPage) {
            resp.sendRedirect("/");
        } else {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("text/plain;charset=UTF-8");
            resp.getWriter().write("Access denied: please log in to Headwind MDM first.");
        }
    }

    private String extractJSessionId(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return null;
        }
        String[] parts = cookieHeader.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("JSESSIONID=")) {
                String val = trimmed.substring("JSESSIONID=".length()).trim();
                if (!val.isEmpty()) {
                    return val;
                }
            }
        }
        return null;
    }

    private boolean isAuthorizedHmdmSession(String jsessionId, String cookieHeader) {
        long now = System.currentTimeMillis();
        Long cachedUntil = VALID_SESSIONS.get(jsessionId);
        if (cachedUntil != null && cachedUntil > now) {
            return true;
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:8080/rest/private/users/current");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestProperty("Cookie", cookieHeader);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code != 200) {
                VALID_SESSIONS.remove(jsessionId);
                return false;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            String body = sb.toString();
            boolean validUser = body.contains("\"status\":\"OK\"")
                    && !body.contains("\"data\":null")
                    && (body.contains("\"plugin_push_send\"")
                        || body.contains("\"edit_devices\"")
                        || body.contains("\"superadmin\""));

            if (validUser) {
                if (VALID_SESSIONS.size() > 500) {
                    VALID_SESSIONS.clear();
                }
                VALID_SESSIONS.put(jsessionId, now + CACHE_TTL_MS);
                return true;
            } else {
                VALID_SESSIONS.remove(jsessionId);
                return false;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public void destroy() {
        VALID_SESSIONS.clear();
    }
}
