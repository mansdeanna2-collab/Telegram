/*
 * Backend Management System - API Client
 * Handles HTTP communication between the Android app and the custom backend server.
 */

package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackendApiClient {

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 15000;
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ApiCallback {
        void onSuccess(JSONObject response);
        void onError(int statusCode, String error);
    }

    /**
     * Login to the backend server with username and password.
     */
    public static void login(String username, String password, ApiCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("username", username);
            body.put("password", password);
        } catch (JSONException e) {
            mainHandler.post(() -> callback.onError(-1, "Failed to create request"));
            return;
        }
        post("/api/auth/login", body, null, callback);
    }

    /**
     * Get current user profile.
     */
    public static void getProfile(String token, ApiCallback callback) {
        get("/api/auth/profile", token, callback);
    }

    /**
     * Update current user profile.
     */
    public static void updateProfile(String token, JSONObject data, ApiCallback callback) {
        put("/api/auth/profile", data, token, callback);
    }

    /**
     * Change password.
     */
    public static void changePassword(String token, String oldPassword, String newPassword, ApiCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("old_password", oldPassword);
            body.put("new_password", newPassword);
        } catch (JSONException e) {
            mainHandler.post(() -> callback.onError(-1, "Failed to create request"));
            return;
        }
        post("/api/auth/change-password", body, token, callback);
    }

    /**
     * Health check.
     */
    public static void healthCheck(ApiCallback callback) {
        get("/api/health", null, callback);
    }

    // --- HTTP Methods ---

    private static void get(String path, String token, ApiCallback callback) {
        executor.execute(() -> {
            try {
                String url = getBaseUrl() + path;
                HttpURLConnection conn = createConnection(url, "GET", token);
                handleResponse(conn, callback);
            } catch (IOException e) {
                mainHandler.post(() -> callback.onError(-1, "Connection failed: " + e.getMessage()));
            }
        });
    }

    private static void post(String path, JSONObject body, String token, ApiCallback callback) {
        executor.execute(() -> {
            try {
                String url = getBaseUrl() + path;
                HttpURLConnection conn = createConnection(url, "POST", token);
                writeBody(conn, body);
                handleResponse(conn, callback);
            } catch (IOException e) {
                mainHandler.post(() -> callback.onError(-1, "Connection failed: " + e.getMessage()));
            }
        });
    }

    private static void put(String path, JSONObject body, String token, ApiCallback callback) {
        executor.execute(() -> {
            try {
                String url = getBaseUrl() + path;
                HttpURLConnection conn = createConnection(url, "PUT", token);
                writeBody(conn, body);
                handleResponse(conn, callback);
            } catch (IOException e) {
                mainHandler.post(() -> callback.onError(-1, "Connection failed: " + e.getMessage()));
            }
        });
    }

    private static HttpURLConnection createConnection(String urlString, String method, String token) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }

        return conn;
    }

    private static void writeBody(HttpURLConnection conn, JSONObject body) throws IOException {
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
    }

    private static void handleResponse(HttpURLConnection conn, ApiCallback callback) throws IOException {
        int statusCode = conn.getResponseCode();

        BufferedReader reader;
        if (statusCode >= 200 && statusCode < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }

        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        String responseStr = response.toString();

        mainHandler.post(() -> {
            try {
                JSONObject json = new JSONObject(responseStr);
                if (statusCode >= 200 && statusCode < 300) {
                    callback.onSuccess(json);
                } else {
                    String error = json.optString("error", "Unknown error");
                    callback.onError(statusCode, error);
                }
            } catch (JSONException e) {
                if (statusCode >= 200 && statusCode < 300) {
                    try {
                        callback.onSuccess(new JSONObject().put("raw", responseStr));
                    } catch (JSONException ex) {
                        callback.onError(statusCode, "Failed to parse response");
                    }
                } else {
                    callback.onError(statusCode, "Server error: " + statusCode);
                }
            }
        });
    }

    private static String getBaseUrl() {
        BackendConfig config = BackendConfig.getInstance();
        if (config != null) {
            return config.getBackendUrl();
        }
        return BackendConfig.DEFAULT_BACKEND_URL;
    }
}
