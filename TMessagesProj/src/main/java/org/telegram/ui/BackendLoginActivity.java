/*
 * Backend Management System - Backend Login Fragment
 * Provides username/password login interface for the custom backend.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BackendApiClient;
import org.telegram.messenger.BackendConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class BackendLoginActivity extends BaseFragment {

    // Offset added to backend user IDs to avoid conflicts with Telegram user IDs
    private static final long BACKEND_USER_ID_OFFSET = 1000000L;

    private EditText serverUrlField;
    private EditText usernameField;
    private EditText passwordField;
    private TextView loginButton;
    private TextView switchToTelegramButton;
    private ProgressBar progressBar;
    private boolean isLoading = false;

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Backend Login");
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), dp(32), dp(24), dp(24));
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        scrollView.addView(container, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Logo / Icon
        ImageView logoView = new ImageView(context);
        logoView.setImageResource(R.drawable.msg_settings);
        logoView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.SRC_IN));
        container.addView(logoView, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // Title
        TextView titleView = new TextView(context);
        titleView.setText("Backend Management Login");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        titleView.setTypeface(AndroidUtilities.bold());
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        // Subtitle
        TextView subtitleView = new TextView(context);
        subtitleView.setText("Login with your backend account credentials");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitleView.setGravity(Gravity.CENTER);
        container.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // Server URL field
        TextView serverLabel = new TextView(context);
        serverLabel.setText("Server URL");
        serverLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        serverLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        container.addView(serverLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        serverUrlField = new EditText(context);
        serverUrlField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        serverUrlField.setHint(BackendConfig.DEFAULT_BACKEND_URL);
        serverUrlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverUrlField.setSingleLine(true);
        serverUrlField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        serverUrlField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        serverUrlField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        serverUrlField.setPadding(dp(12), dp(10), dp(12), dp(10));
        BackendConfig config = BackendConfig.getInstance();
        if (config != null) {
            String savedUrl = config.getBackendUrl();
            if (!TextUtils.isEmpty(savedUrl)) {
                serverUrlField.setText(savedUrl);
            }
        }
        container.addView(serverUrlField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // Username field
        TextView usernameLabel = new TextView(context);
        usernameLabel.setText("Username");
        usernameLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        usernameLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        container.addView(usernameLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        usernameField = new EditText(context);
        usernameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        usernameField.setHint("Enter username");
        usernameField.setInputType(InputType.TYPE_CLASS_TEXT);
        usernameField.setSingleLine(true);
        usernameField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        usernameField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        usernameField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        usernameField.setPadding(dp(12), dp(10), dp(12), dp(10));
        container.addView(usernameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // Password field
        TextView passwordLabel = new TextView(context);
        passwordLabel.setText("Password");
        passwordLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        passwordLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        container.addView(passwordLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        passwordField = new EditText(context);
        passwordField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        passwordField.setHint("Enter password");
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setSingleLine(true);
        passwordField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        passwordField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        passwordField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        passwordField.setPadding(dp(12), dp(10), dp(12), dp(10));
        passwordField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passwordField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performLogin();
                return true;
            }
            return false;
        });
        container.addView(passwordField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // Progress bar
        progressBar = new ProgressBar(context);
        progressBar.setVisibility(View.GONE);
        container.addView(progressBar, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // Login button
        loginButton = new TextView(context);
        loginButton.setText("Login");
        loginButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        loginButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        loginButton.setBackgroundColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        loginButton.setGravity(Gravity.CENTER);
        loginButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        loginButton.setTypeface(AndroidUtilities.bold());
        loginButton.setOnClickListener(v -> performLogin());
        container.addView(loginButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 16));

        // Switch to Telegram login
        switchToTelegramButton = new TextView(context);
        switchToTelegramButton.setText("Use Telegram Login Instead");
        switchToTelegramButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        switchToTelegramButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
        switchToTelegramButton.setGravity(Gravity.CENTER);
        switchToTelegramButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        switchToTelegramButton.setOnClickListener(v -> {
            BackendConfig backendConfig = BackendConfig.getInstance();
            if (backendConfig != null) {
                backendConfig.setBackendEnabled(false);
            }
            presentFragment(new LoginActivity(), true);
        });
        container.addView(switchToTelegramButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void performLogin() {
        if (isLoading) {
            return;
        }

        String serverUrl = serverUrlField.getText().toString().trim();
        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString();

        if (TextUtils.isEmpty(username)) {
            shakeField(usernameField);
            return;
        }
        if (TextUtils.isEmpty(password)) {
            shakeField(passwordField);
            return;
        }

        // Save the server URL
        BackendConfig config = BackendConfig.getInstance();
        if (config != null && !TextUtils.isEmpty(serverUrl)) {
            config.setBackendUrl(serverUrl);
        }

        setLoading(true);

        BackendApiClient.login(username, password, new BackendApiClient.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                setLoading(false);
                try {
                    String token = response.getString("token");
                    JSONObject userObj = response.getJSONObject("user");

                    int userId = userObj.getInt("id");
                    String uname = userObj.optString("username", "");
                    String firstName = userObj.optString("first_name", "");
                    String lastName = userObj.optString("last_name", "");
                    String phone = userObj.optString("phone", "");

                    // Save backend session
                    if (config != null) {
                        config.setAuthToken(token);
                        config.setBackendEnabled(true);
                        config.saveUserInfo(userId, uname, firstName, lastName, phone);
                    }

                    // Create TLRPC.User and set as current user for the app
                    onBackendLoginSuccess(userId, firstName, lastName, uname, phone);

                } catch (Exception e) {
                    showError("Failed to parse login response");
                }
            }

            @Override
            public void onError(int statusCode, String error) {
                setLoading(false);
                showError(error);
            }
        });
    }

    private void onBackendLoginSuccess(int userId, String firstName, String lastName, String username, String phone) {
        // Create a TLRPC.User to integrate with the existing app framework
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = BACKEND_USER_ID_OFFSET + userId;
        user.first_name = !TextUtils.isEmpty(firstName) ? firstName : username;
        user.last_name = lastName != null ? lastName : "";
        user.username = username;
        user.phone = phone != null ? phone : "";
        user.status = new TLRPC.TL_userStatusOnline();
        user.status.expires = Integer.MAX_VALUE;
        // TLRPC.User flags: 1 = has first_name, 2 = has last_name, 4 = has username
        user.flags = 1 | 2 | 4;

        // Set the user in UserConfig to mark as logged in
        MessagesController.getInstance(currentAccount).cleanup();
        UserConfig.getInstance(currentAccount).clearConfig();
        UserConfig.getInstance(currentAccount).setCurrentUser(user);
        UserConfig.getInstance(currentAccount).saveConfig(true);

        // Put user in cache
        MessagesController.getInstance(currentAccount).putUser(user, false);
        MessagesStorage.getInstance(currentAccount).putUsersAndChats(java.util.Collections.singletonList(user), null, false, true);

        // Navigate to main screen using MainTabsActivity (same as normal Telegram login flow)
        MainTabsActivity mainTabsActivity = new MainTabsActivity();
        presentFragment(mainTabsActivity, true);
    }

    private void setLoading(boolean loading) {
        isLoading = loading;
        if (progressBar != null) {
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (loginButton != null) {
            loginButton.setEnabled(!loading);
            loginButton.setAlpha(loading ? 0.5f : 1.0f);
        }
    }

    private void showError(String message) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Login Error");
        builder.setMessage(message);
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private void shakeField(View view) {
        AndroidUtilities.shakeView(view);
    }
}
