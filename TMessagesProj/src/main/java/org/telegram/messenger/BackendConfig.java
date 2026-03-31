/*
 * Backend Management System - Configuration
 * Manages the backend server URL and settings for the custom backend.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public class BackendConfig {

    private static final String PREFS_NAME = "backend_config";
    private static final String KEY_BACKEND_URL = "backend_url";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_BACKEND_ENABLED = "backend_enabled";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_FIRST_NAME = "first_name";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_PHONE = "phone";

    // Default backend URL (change to your server address)
    public static final String DEFAULT_BACKEND_URL = "http://10.0.2.2:5000";

    private static volatile BackendConfig instance;
    private final SharedPreferences prefs;

    private BackendConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static BackendConfig getInstance(Context context) {
        if (instance == null) {
            synchronized (BackendConfig.class) {
                if (instance == null) {
                    instance = new BackendConfig(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static BackendConfig getInstance() {
        if (instance == null && ApplicationLoader.applicationContext != null) {
            return getInstance(ApplicationLoader.applicationContext);
        }
        return instance;
    }

    public String getBackendUrl() {
        return prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL);
    }

    public void setBackendUrl(String url) {
        prefs.edit().putString(KEY_BACKEND_URL, url).apply();
    }

    public String getAuthToken() {
        return prefs.getString(KEY_AUTH_TOKEN, null);
    }

    public void setAuthToken(String token) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply();
    }

    public boolean isBackendEnabled() {
        return prefs.getBoolean(KEY_BACKEND_ENABLED, false);
    }

    public void setBackendEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BACKEND_ENABLED, enabled).apply();
    }

    public boolean isLoggedIn() {
        return getAuthToken() != null && getUserId() > 0;
    }

    public void saveUserInfo(int userId, String username, String firstName, String lastName, String phone) {
        prefs.edit()
                .putInt(KEY_USER_ID, userId)
                .putString(KEY_USERNAME, username)
                .putString(KEY_FIRST_NAME, firstName)
                .putString(KEY_LAST_NAME, lastName)
                .putString(KEY_PHONE, phone)
                .apply();
    }

    public int getUserId() {
        return prefs.getInt(KEY_USER_ID, 0);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getFirstName() {
        return prefs.getString(KEY_FIRST_NAME, "");
    }

    public String getLastName() {
        return prefs.getString(KEY_LAST_NAME, "");
    }

    public String getPhone() {
        return prefs.getString(KEY_PHONE, "");
    }

    public void clearSession() {
        prefs.edit()
                .remove(KEY_AUTH_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_USERNAME)
                .remove(KEY_FIRST_NAME)
                .remove(KEY_LAST_NAME)
                .remove(KEY_PHONE)
                .apply();
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
