/*
 * Backend Phone Login - Independent phone number login activity.
 * This activity provides its own phone input and verification code UI,
 * calling the custom backend API directly without any Telegram interception.
 *
 * Flow: phone input → POST /api/auth/send-code → code input → POST /api/auth/verify-code → login
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class BackendPhoneLoginActivity extends BaseFragment {

    private static final int STEP_PHONE = 0;
    private static final int STEP_CODE = 1;

    private static final long BACKEND_USER_ID_OFFSET = 1000000L;
    private static final int CODE_LENGTH = 5;

    private int currentStep = STEP_PHONE;
    private String currentPhone = "";

    // Phone step views
    private LinearLayout phoneContainer;
    private EditText phoneField;
    private TextView nextButton;

    // Code step views
    private LinearLayout codeContainer;
    private EditText codeField;
    private TextView verifyButton;
    private TextView codeInfoText;

    // Shared views
    private ProgressBar progressBar;
    private boolean isLoading = false;

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("");
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
        mainContainer.setPadding(dp(24), dp(48), dp(24), dp(24));
        scrollView.addView(mainContainer, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // ===== Phone Step =====
        phoneContainer = new LinearLayout(context);
        phoneContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.addView(phoneContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Title
        TextView phoneTitleText = new TextView(context);
        phoneTitleText.setText("Your Phone");
        phoneTitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        phoneTitleText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        phoneTitleText.setTypeface(AndroidUtilities.bold());
        phoneTitleText.setGravity(Gravity.CENTER);
        phoneContainer.addView(phoneTitleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Subtitle
        TextView phoneSubtitleText = new TextView(context);
        phoneSubtitleText.setText("Please enter your phone number to login.");
        phoneSubtitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        phoneSubtitleText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        phoneSubtitleText.setGravity(Gravity.CENTER);
        phoneContainer.addView(phoneSubtitleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 32));

        // Phone input
        phoneField = new EditText(context);
        phoneField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        phoneField.setHint("+1 234 567 8900");
        phoneField.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneField.setSingleLine(true);
        phoneField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        phoneField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        phoneField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        phoneField.setPadding(dp(16), dp(14), dp(16), dp(14));
        phoneField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        phoneField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                performSendCode();
                return true;
            }
            return false;
        });
        phoneContainer.addView(phoneField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // Next button
        nextButton = new TextView(context);
        nextButton.setText("Next");
        nextButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nextButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        nextButton.setBackgroundColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        nextButton.setGravity(Gravity.CENTER);
        nextButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        nextButton.setTypeface(AndroidUtilities.bold());
        nextButton.setOnClickListener(v -> performSendCode());
        phoneContainer.addView(nextButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 0));

        // ===== Code Step =====
        codeContainer = new LinearLayout(context);
        codeContainer.setOrientation(LinearLayout.VERTICAL);
        codeContainer.setVisibility(View.GONE);
        mainContainer.addView(codeContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Code title
        TextView codeTitleText = new TextView(context);
        codeTitleText.setText("Enter Code");
        codeTitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        codeTitleText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeTitleText.setTypeface(AndroidUtilities.bold());
        codeTitleText.setGravity(Gravity.CENTER);
        codeContainer.addView(codeTitleText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Code info
        codeInfoText = new TextView(context);
        codeInfoText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        codeInfoText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        codeInfoText.setGravity(Gravity.CENTER);
        codeContainer.addView(codeInfoText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 32));

        // Code input
        codeField = new EditText(context);
        codeField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        codeField.setHint("• • • • •");
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeField.setSingleLine(true);
        codeField.setGravity(Gravity.CENTER);
        codeField.setLetterSpacing(0.5f);
        codeField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        codeField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        codeField.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        codeField.setPadding(dp(16), dp(14), dp(16), dp(14));
        codeField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performVerifyCode();
                return true;
            }
            return false;
        });
        codeField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == CODE_LENGTH) {
                    performVerifyCode();
                }
            }
        });
        codeContainer.addView(codeField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        // Verify button
        verifyButton = new TextView(context);
        verifyButton.setText("Next");
        verifyButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        verifyButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        verifyButton.setBackgroundColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        verifyButton.setGravity(Gravity.CENTER);
        verifyButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        verifyButton.setTypeface(AndroidUtilities.bold());
        verifyButton.setOnClickListener(v -> performVerifyCode());
        codeContainer.addView(verifyButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 0));

        // ===== Shared progress bar =====
        progressBar = new ProgressBar(context);
        progressBar.setVisibility(View.GONE);
        mainContainer.addView(progressBar, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ((FrameLayout) fragmentView).addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void showPhoneStep() {
        currentStep = STEP_PHONE;
        phoneContainer.setVisibility(View.VISIBLE);
        codeContainer.setVisibility(View.GONE);
        actionBar.setTitle("");
        if (phoneField != null) {
            phoneField.requestFocus();
            AndroidUtilities.showKeyboard(phoneField);
        }
    }

    private void showCodeStep() {
        currentStep = STEP_CODE;
        phoneContainer.setVisibility(View.GONE);
        codeContainer.setVisibility(View.VISIBLE);
        if (codeInfoText != null) {
            codeInfoText.setText("We've sent a code to " + currentPhone);
        }
        if (codeField != null) {
            codeField.setText("");
            codeField.requestFocus();
            AndroidUtilities.showKeyboard(codeField);
        }
    }

    private void performSendCode() {
        if (isLoading) {
            return;
        }

        String phone = phoneField.getText().toString().trim();
        if (TextUtils.isEmpty(phone)) {
            AndroidUtilities.shakeView(phoneField);
            return;
        }

        // Normalize phone: ensure it starts with +
        if (!phone.startsWith("+")) {
            phone = "+" + phone;
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
            AndroidUtilities.shakeView(codeField);
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
                    String userPhone = userObj.optString("phone", "");

                    // Save backend session
                    BackendConfig config = BackendConfig.getInstance();
                    if (config != null) {
                        config.setAuthToken(token);
                        config.setBackendEnabled(true);
                        config.saveUserInfo(userId, username, firstName, lastName, userPhone);
                    }

                    onLoginSuccess(userId, firstName, lastName, username, userPhone);
                } catch (Exception e) {
                    showError("Failed to parse login response");
                }
            }

            @Override
            public void onError(int statusCode, String error) {
                setLoading(false);
                AndroidUtilities.shakeView(codeField);
                showError(error);
            }
        });
    }

    private void onLoginSuccess(int userId, String firstName, String lastName, String username, String phone) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = BACKEND_USER_ID_OFFSET + userId;
        user.first_name = !TextUtils.isEmpty(firstName) ? firstName : username;
        user.last_name = lastName != null ? lastName : "";
        user.username = username;
        user.phone = phone != null ? phone : "";
        user.status = new TLRPC.TL_userStatusOnline();
        user.status.expires = Integer.MAX_VALUE;
        user.flags = 1 | 2 | 4;

        MessagesController.getInstance(currentAccount).cleanup();
        UserConfig.getInstance(currentAccount).clearConfig();
        UserConfig.getInstance(currentAccount).setCurrentUser(user);
        UserConfig.getInstance(currentAccount).saveConfig(true);

        MessagesController.getInstance(currentAccount).putUser(user, false);
        MessagesStorage.getInstance(currentAccount).putUsersAndChats(
                java.util.Collections.singletonList(user), null, false, true);

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
            if (nextButton != null) {
                nextButton.setEnabled(!loading);
                nextButton.setAlpha(loading ? 0.5f : 1.0f);
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
        builder.setTitle("Error");
        builder.setMessage(message);
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    @Override
    public void onBackPressed() {
        if (currentStep == STEP_CODE) {
            showPhoneStep();
        } else {
            super.onBackPressed();
        }
    }
}
