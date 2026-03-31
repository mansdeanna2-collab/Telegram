/*
 * Backend Management System - Phone Login Fragment
 * Provides phone number + verification code login interface for the custom backend.
 * Flow: Enter phone → backend generates code (admin sees it) → enter code → login
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

public class BackendPhoneLoginActivity extends BaseFragment {

    // Offset added to backend user IDs to avoid conflicts with Telegram user IDs
    private static final long BACKEND_USER_ID_OFFSET = 1000000L;

    private static final int STEP_PHONE = 0;
    private static final int STEP_CODE = 1;

    private int currentStep = STEP_PHONE;
    private String currentPhone = "";

    // Phone step views
    private LinearLayout phoneContainer;
    private EditText serverUrlField;
    private EditText phoneField;
    private TextView sendCodeButton;

    // Code step views
    private LinearLayout codeContainer;
    private EditText codeField;
    private TextView verifyButton;
    private TextView resendCodeButton;
    private TextView codeInfoText;

    // Shared views
    private ProgressBar progressBar;
    private TextView switchToUsernameButton;
    private boolean isLoading = false;

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Phone Login");
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (currentStep == STEP_CODE) {
                        showPhoneStep();
                    } else {
                        finishFragment();
                    }
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout mainContainer = new LinearLayout(context);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setPadding(dp(24), dp(32), dp(24), dp(24));
        mainContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        scrollView.addView(mainContainer, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Logo / Icon
        ImageView logoView = new ImageView(context);
        logoView.setImageResource(R.drawable.msg_settings);
        logoView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.SRC_IN));
        mainContainer.addView(logoView, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        // ====== Phone Step ======
        phoneContainer = new LinearLayout(context);
        phoneContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.addView(phoneContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Title
        TextView phoneTitleView = new TextView(context);
        phoneTitleView.setText("Phone Login");
        phoneTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        phoneTitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        phoneTitleView.setGravity(Gravity.CENTER);
        phoneTitleView.setTypeface(AndroidUtilities.bold());
        phoneContainer.addView(phoneTitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        // Subtitle
        TextView phoneSubtitleView = new TextView(context);
        phoneSubtitleView.setText("Enter your phone number to receive a verification code");
        phoneSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        phoneSubtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        phoneSubtitleView.setGravity(Gravity.CENTER);
        phoneContainer.addView(phoneSubtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // Server URL field
        TextView serverLabel = new TextView(context);
        serverLabel.setText("Server URL");
        serverLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        serverLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        phoneContainer.addView(serverLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

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
        phoneContainer.addView(serverUrlField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // Phone number field
        TextView phoneLabel = new TextView(context);
        phoneLabel.setText("Phone Number");
        phoneLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        phoneLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        phoneContainer.addView(phoneLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        phoneField = new EditText(context);
        phoneField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        phoneField.setHint("+1234567890");
        phoneField.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneField.setSingleLine(true);
        phoneField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        phoneField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        phoneField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        phoneField.setPadding(dp(12), dp(10), dp(12), dp(10));
        phoneField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        phoneField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performSendCode();
                return true;
            }
            return false;
        });
        phoneContainer.addView(phoneField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // Send code button
        sendCodeButton = new TextView(context);
        sendCodeButton.setText("Send Verification Code");
        sendCodeButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        sendCodeButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        sendCodeButton.setBackgroundColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        sendCodeButton.setGravity(Gravity.CENTER);
        sendCodeButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        sendCodeButton.setTypeface(AndroidUtilities.bold());
        sendCodeButton.setOnClickListener(v -> performSendCode());
        phoneContainer.addView(sendCodeButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 0));

        // ====== Code Step ======
        codeContainer = new LinearLayout(context);
        codeContainer.setOrientation(LinearLayout.VERTICAL);
        codeContainer.setVisibility(View.GONE);
        mainContainer.addView(codeContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Code step title
        TextView codeTitleView = new TextView(context);
        codeTitleView.setText("Enter Verification Code");
        codeTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        codeTitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeTitleView.setGravity(Gravity.CENTER);
        codeTitleView.setTypeface(AndroidUtilities.bold());
        codeContainer.addView(codeTitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        // Code info text (shows which phone number)
        codeInfoText = new TextView(context);
        codeInfoText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        codeInfoText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        codeInfoText.setGravity(Gravity.CENTER);
        codeContainer.addView(codeInfoText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // Code field
        TextView codeLabel = new TextView(context);
        codeLabel.setText("Verification Code");
        codeLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        codeLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeContainer.addView(codeLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        codeField = new EditText(context);
        codeField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        codeField.setHint("Enter 5-digit code");
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeField.setSingleLine(true);
        codeField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        codeField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        codeField.setPadding(dp(12), dp(10), dp(12), dp(10));
        codeField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performVerifyCode();
                return true;
            }
            return false;
        });
        codeContainer.addView(codeField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // Verify button
        verifyButton = new TextView(context);
        verifyButton.setText("Verify Code");
        verifyButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        verifyButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        verifyButton.setBackgroundColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        verifyButton.setGravity(Gravity.CENTER);
        verifyButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        verifyButton.setTypeface(AndroidUtilities.bold());
        verifyButton.setOnClickListener(v -> performVerifyCode());
        codeContainer.addView(verifyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 16));

        // Resend code button
        resendCodeButton = new TextView(context);
        resendCodeButton.setText("Resend Code");
        resendCodeButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        resendCodeButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
        resendCodeButton.setGravity(Gravity.CENTER);
        resendCodeButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        resendCodeButton.setOnClickListener(v -> performSendCode());
        codeContainer.addView(resendCodeButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

        // ====== Shared views ======

        // Progress bar
        progressBar = new ProgressBar(context);
        progressBar.setVisibility(View.GONE);
        mainContainer.addView(progressBar, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 16));

        // Switch to username/password login
        switchToUsernameButton = new TextView(context);
        switchToUsernameButton.setText("Use Username/Password Login");
        switchToUsernameButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        switchToUsernameButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2));
        switchToUsernameButton.setGravity(Gravity.CENTER);
        switchToUsernameButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        switchToUsernameButton.setOnClickListener(v -> {
            presentFragment(new BackendLoginActivity(), true);
        });
        mainContainer.addView(switchToUsernameButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ((FrameLayout) fragmentView).addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void showPhoneStep() {
        currentStep = STEP_PHONE;
        phoneContainer.setVisibility(View.VISIBLE);
        codeContainer.setVisibility(View.GONE);
        actionBar.setTitle("Phone Login");
    }

    private void showCodeStep() {
        currentStep = STEP_CODE;
        phoneContainer.setVisibility(View.GONE);
        codeContainer.setVisibility(View.VISIBLE);
        codeInfoText.setText("A verification code has been generated for " + currentPhone + ".\nPlease get the code from your administrator.");
        codeField.setText("");
        codeField.requestFocus();
        actionBar.setTitle("Verification Code");
    }

    private void performSendCode() {
        if (isLoading) {
            return;
        }

        String serverUrl = serverUrlField.getText().toString().trim();
        String phone = phoneField.getText().toString().trim();

        if (TextUtils.isEmpty(phone)) {
            shakeField(phoneField);
            return;
        }

        // Save the server URL
        BackendConfig config = BackendConfig.getInstance();
        if (config != null && !TextUtils.isEmpty(serverUrl)) {
            config.setBackendUrl(serverUrl);
        }

        currentPhone = phone;
        setLoading(true);

        BackendApiClient.sendCode(phone, new BackendApiClient.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                setLoading(false);
                showCodeStep();
            }

            @Override
            public void onError(int statusCode, String error) {
                setLoading(false);
                showError(error);
            }
        });
    }

    private void performVerifyCode() {
        if (isLoading) {
            return;
        }

        String code = codeField.getText().toString().trim();
        if (TextUtils.isEmpty(code)) {
            shakeField(codeField);
            return;
        }

        setLoading(true);

        BackendApiClient.verifyCode(currentPhone, code, new BackendApiClient.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                setLoading(false);
                try {
                    String token = response.getString("token");
                    JSONObject userObj = response.getJSONObject("user");

                    int userId = userObj.getInt("id");
                    String username = userObj.optString("username", "");
                    String firstName = userObj.optString("first_name", "");
                    String lastName = userObj.optString("last_name", "");
                    String phone = userObj.optString("phone", "");

                    // Save backend session
                    BackendConfig config = BackendConfig.getInstance();
                    if (config != null) {
                        config.setAuthToken(token);
                        config.setBackendEnabled(true);
                        config.saveUserInfo(userId, username, firstName, lastName, phone);
                    }

                    // Create TLRPC.User and set as current user for the app
                    onBackendLoginSuccess(userId, firstName, lastName, username, phone);

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

        // Navigate to main screen
        MainTabsActivity mainTabsActivity = new MainTabsActivity();
        presentFragment(mainTabsActivity, true);
    }

    private void setLoading(boolean loading) {
        isLoading = loading;
        if (progressBar != null) {
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (currentStep == STEP_PHONE) {
            if (sendCodeButton != null) {
                sendCodeButton.setEnabled(!loading);
                sendCodeButton.setAlpha(loading ? 0.5f : 1.0f);
            }
        } else {
            if (verifyButton != null) {
                verifyButton.setEnabled(!loading);
                verifyButton.setAlpha(loading ? 0.5f : 1.0f);
            }
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
