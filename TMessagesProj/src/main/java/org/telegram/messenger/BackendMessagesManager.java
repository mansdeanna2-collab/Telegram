/*
 * Backend Messages Manager
 * Bridges the backend messaging API with the Telegram TLRPC data structures.
 * When backend mode is enabled, this class handles loading dialogs, messages,
 * and contacts from the custom backend server and injects them into the existing
 * Telegram UI framework without modifying the frontend.
 */

package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.util.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;

public class BackendMessagesManager extends BaseController {

    private static final long BACKEND_USER_ID_OFFSET = 1000000L;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static volatile BackendMessagesManager[] Instance = new BackendMessagesManager[UserConfig.MAX_ACCOUNT_COUNT];
    private boolean loadingDialogs = false;
    private boolean dialogsLoaded = false;

    // Maps backend conversation ID to dialog ID for quick lookup
    private final HashMap<Integer, Long> conversationToDialogMap = new HashMap<>();
    // Maps dialog ID to backend conversation ID
    private final HashMap<Long, Integer> dialogToConversationMap = new HashMap<>();

    public static BackendMessagesManager getInstance(int num) {
        BackendMessagesManager localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (BackendMessagesManager.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new BackendMessagesManager(num);
                }
            }
        }
        return localInstance;
    }

    public BackendMessagesManager(int num) {
        super(num);
    }

    /**
     * Check if the backend messaging system should be used.
     */
    public static boolean isBackendMode() {
        BackendConfig config = BackendConfig.getInstance();
        return config != null && config.isBackendEnabled() && config.isLoggedIn();
    }

    /**
     * Get the auth token.
     */
    private String getToken() {
        BackendConfig config = BackendConfig.getInstance();
        return config != null ? config.getAuthToken() : null;
    }

    /**
     * Get the current backend user ID.
     */
    private int getBackendUserId() {
        BackendConfig config = BackendConfig.getInstance();
        return config != null ? config.getUserId() : 0;
    }

    /**
     * Convert a backend user ID to a TLRPC-compatible user ID.
     */
    public static long toTlrpcUserId(int backendUserId) {
        return BACKEND_USER_ID_OFFSET + backendUserId;
    }

    /**
     * Convert a TLRPC user ID back to a backend user ID.
     */
    public static int toBackendUserId(long tlrpcUserId) {
        return (int) (tlrpcUserId - BACKEND_USER_ID_OFFSET);
    }

    /**
     * Check if a user ID belongs to a backend user.
     */
    public static boolean isBackendUserId(long userId) {
        return userId >= BACKEND_USER_ID_OFFSET;
    }

    /**
     * Get backend conversation ID for a dialog.
     */
    public Integer getConversationId(long dialogId) {
        return dialogToConversationMap.get(dialogId);
    }

    // ==================== DIALOG LOADING ====================

    /**
     * Load dialogs (conversations) from the backend API.
     * Creates TLRPC.Dialog objects and injects them into MessagesController.
     */
    public void loadDialogsFromBackend() {
        if (loadingDialogs) {
            return;
        }
        loadingDialogs = true;
        String token = getToken();
        if (token == null) {
            loadingDialogs = false;
            return;
        }

        BackendApiClient.getConversations(token, new BackendApiClient.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                try {
                    JSONArray conversations = response.getJSONArray("conversations");
                    processConversations(conversations);
                } catch (JSONException e) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("BackendMessagesManager: Failed to parse conversations", e);
                    }
                }
                loadingDialogs = false;
                dialogsLoaded = true;
            }

            @Override
            public void onError(int statusCode, String error) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("BackendMessagesManager: Failed to load conversations: " + error);
                }
                loadingDialogs = false;
                // Mark as loaded but empty to prevent re-loading loop
                dialogsLoaded = true;
                finishDialogLoading();
            }
        });
    }

    /**
     * Process conversations from the backend and inject into MessagesController.
     */
    private void processConversations(JSONArray conversations) throws JSONException {
        MessagesController controller = getMessagesController();
        int currentAccount = this.currentAccount;

        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
        ArrayList<TLRPC.Message> messages = new ArrayList<>();

        for (int i = 0; i < conversations.length(); i++) {
            JSONObject conv = conversations.getJSONObject(i);
            int convId = conv.getInt("id");

            // Get the other user in the conversation
            JSONObject otherUser = conv.optJSONObject("other_user");
            if (otherUser == null) continue;

            int otherBackendUserId = otherUser.getInt("id");
            long otherTlrpcUserId = toTlrpcUserId(otherBackendUserId);

            // Create TLRPC.User for the other participant
            TLRPC.TL_user user = createTlrpcUser(otherUser);
            users.add(user);

            // Map conversation <-> dialog
            conversationToDialogMap.put(convId, otherTlrpcUserId);
            dialogToConversationMap.put(otherTlrpcUserId, convId);

            // Create TLRPC.Dialog
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
            dialog.id = otherTlrpcUserId;
            dialog.peer = new TLRPC.TL_peerUser();
            dialog.peer.user_id = otherTlrpcUserId;
            dialog.unread_count = conv.optInt("unread_count", 0);
            dialog.unread_mentions_count = 0;
            dialog.unread_reactions_count = 0;
            dialog.read_inbox_max_id = 0;
            dialog.read_outbox_max_id = 0;
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            dialog.folder_id = 0;
            dialog.pinned = false;
            dialog.unread_mark = false;

            // Process last message if available
            JSONObject lastMsg = conv.optJSONObject("last_message");
            if (lastMsg != null) {
                TLRPC.TL_message tlMessage = createTlrpcMessage(lastMsg, otherTlrpcUserId);
                dialog.top_message = tlMessage.id;
                dialog.last_message_date = tlMessage.date;
                messages.add(tlMessage);
            } else {
                dialog.top_message = 0;
                dialog.last_message_date = parseDate(conv.optString("created_at", ""));
            }

            dialogs.add(dialog);
        }

        // Inject users into MessagesController
        for (TLRPC.User user : users) {
            controller.putUser(user, true);
        }

        // Inject dialogs and messages into MessagesController
        LongSparseArray<TLRPC.User> usersDict = new LongSparseArray<>();
        for (TLRPC.User user : users) {
            usersDict.put(user.id, user);
        }

        // Clear existing backend dialogs and re-add
        ArrayList<TLRPC.Dialog> allDialogs = controller.getAllDialogs();

        for (TLRPC.Dialog dialog : dialogs) {
            // Remove old entry if exists
            TLRPC.Dialog old = controller.dialogs_dict.get(dialog.id);
            if (old != null) {
                allDialogs.remove(old);
            }
            controller.dialogs_dict.put(dialog.id, dialog);
            allDialogs.add(dialog);

            // Add message objects for the last message
            for (TLRPC.Message msg : messages) {
                if (msg.dialog_id == dialog.id) {
                    MessageObject messageObject = new MessageObject(currentAccount, msg, usersDict, null, false, false);
                    ArrayList<MessageObject> msgList = new ArrayList<>();
                    msgList.add(messageObject);
                    controller.dialogMessage.put(dialog.id, msgList);
                }
            }
        }

        // Setup folder
        ArrayList<TLRPC.Dialog> folder0 = controller.dialogsByFolder.get(0);
        if (folder0 == null) {
            folder0 = new ArrayList<>();
            controller.dialogsByFolder.put(0, folder0);
        }
        // Add non-duplicate dialogs to folder
        for (TLRPC.Dialog dialog : dialogs) {
            boolean found = false;
            for (int i = 0; i < folder0.size(); i++) {
                if (folder0.get(i).id == dialog.id) {
                    folder0.set(i, dialog);
                    found = true;
                    break;
                }
            }
            if (!found) {
                folder0.add(dialog);
            }
        }

        // Sort dialogs by date
        controller.sortDialogs(null);

        // Finish loading
        finishDialogLoading();
    }

    private void finishDialogLoading() {
        MessagesController controller = getMessagesController();
        controller.loadingDialogs.put(0, false);
        controller.dialogsEndReached.put(0, true);
        controller.serverDialogsEndReached.put(0, true);
        controller.dialogsLoaded = true;
        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    // ==================== MESSAGE LOADING ====================

    /**
     * Load messages for a specific dialog from the backend API.
     */
    public void loadMessagesFromBackend(long dialogId, int classGuid, int loadIndex) {
        Integer convId = getConversationId(dialogId);
        if (convId == null) {
            // Dialog not found in our mapping; it might be a new conversation
            // Try to create/get the conversation first
            int backendUserId = toBackendUserId(dialogId);
            String token = getToken();
            if (token == null) {
                postEmptyMessages(dialogId, classGuid, loadIndex);
                return;
            }
            BackendApiClient.createConversation(token, backendUserId, new BackendApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    try {
                        JSONObject conv = response.getJSONObject("conversation");
                        int newConvId = conv.getInt("id");
                        conversationToDialogMap.put(newConvId, dialogId);
                        dialogToConversationMap.put(dialogId, newConvId);
                        fetchMessages(newConvId, dialogId, classGuid, loadIndex);
                    } catch (JSONException e) {
                        postEmptyMessages(dialogId, classGuid, loadIndex);
                    }
                }

                @Override
                public void onError(int statusCode, String error) {
                    postEmptyMessages(dialogId, classGuid, loadIndex);
                }
            });
            return;
        }
        fetchMessages(convId, dialogId, classGuid, loadIndex);
    }

    private void fetchMessages(int conversationId, long dialogId, int classGuid, int loadIndex) {
        String token = getToken();
        if (token == null) {
            postEmptyMessages(dialogId, classGuid, loadIndex);
            return;
        }

        BackendApiClient.getMessages(token, conversationId, 1, 50, new BackendApiClient.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                try {
                    JSONArray messagesArr = response.getJSONArray("messages");
                    processMessages(messagesArr, dialogId, classGuid, loadIndex);
                } catch (JSONException e) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("BackendMessagesManager: Failed to parse messages", e);
                    }
                    postEmptyMessages(dialogId, classGuid, loadIndex);
                }
            }

            @Override
            public void onError(int statusCode, String error) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("BackendMessagesManager: Failed to load messages: " + error);
                }
                postEmptyMessages(dialogId, classGuid, loadIndex);
            }
        });
    }

    private void processMessages(JSONArray messagesArr, long dialogId, int classGuid, int loadIndex) throws JSONException {
        ArrayList<MessageObject> messageObjects = new ArrayList<>();
        int currentAccount = this.currentAccount;

        // Build user dict for message object creation
        LongSparseArray<TLRPC.User> usersDict = new LongSparseArray<>();
        TLRPC.User currentUser = getUserConfig().getCurrentUser();
        if (currentUser != null) {
            usersDict.put(currentUser.id, currentUser);
        }

        for (int i = 0; i < messagesArr.length(); i++) {
            JSONObject msgJson = messagesArr.getJSONObject(i);
            TLRPC.TL_message tlMessage = createTlrpcMessage(msgJson, dialogId);

            // Add the sender user to dict
            TLRPC.User sender = getMessagesController().getUser(tlMessage.from_id.user_id);
            if (sender != null) {
                usersDict.put(sender.id, sender);
            }

            HashMap<Long, TLRPC.User> userMap = new HashMap<>();
            for (int j = 0; j < usersDict.size(); j++) {
                userMap.put(usersDict.keyAt(j), usersDict.valueAt(j));
            }

            MessageObject messageObject = new MessageObject(currentAccount, tlMessage, userMap, null, true, true);
            messageObject.messageOwner.send_state = 0; // Sent
            messageObjects.add(messageObject);
        }

        // Post the messagesDidLoad notification with the standard args
        getNotificationCenter().postNotificationName(
                NotificationCenter.messagesDidLoad,
                dialogId,                          // args[0]: dialogId
                messageObjects.size(),             // args[1]: count
                messageObjects,                    // args[2]: messages
                false,                             // args[3]: isCache
                0,                                 // args[4]: first_unread
                0,                                 // args[5]: last_message_id
                0,                                 // args[6]: unread_count
                0,                                 // args[7]: last_date
                4,                                 // args[8]: load_type (initial)
                true,                              // args[9]: isEnd
                classGuid,                         // args[10]: classGuid
                loadIndex,                         // args[11]: loadIndex
                0L,                                // args[12]: max_id
                0,                                 // args[13]: mentionsCount
                0                                  // args[14]: mode
        );
    }

    private void postEmptyMessages(long dialogId, int classGuid, int loadIndex) {
        getNotificationCenter().postNotificationName(
                NotificationCenter.messagesDidLoad,
                dialogId,
                0,
                new ArrayList<MessageObject>(),
                false,
                0,
                0,
                0,
                0,
                4,
                true,
                classGuid,
                loadIndex,
                0L,
                0,
                0
        );
    }

    // ==================== MESSAGE SENDING ====================

    /**
     * Send a text message through the backend API.
     */
    public void sendMessageToBackend(String text, long dialogId) {
        Integer convId = getConversationId(dialogId);
        String token = getToken();
        if (token == null) return;

        if (convId == null) {
            // Need to create conversation first
            int backendUserId = toBackendUserId(dialogId);
            BackendApiClient.createConversation(token, backendUserId, new BackendApiClient.ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    try {
                        JSONObject conv = response.getJSONObject("conversation");
                        int newConvId = conv.getInt("id");
                        conversationToDialogMap.put(newConvId, dialogId);
                        dialogToConversationMap.put(dialogId, newConvId);
                        doSendMessage(token, newConvId, text, dialogId);
                    } catch (JSONException e) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.e("BackendMessagesManager: Failed to create conversation", e);
                        }
                    }
                }

                @Override
                public void onError(int statusCode, String error) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("BackendMessagesManager: Failed to create conversation: " + error);
                    }
                }
            });
            return;
        }

        doSendMessage(token, convId, text, dialogId);
    }

    private void doSendMessage(String token, int conversationId, String text, long dialogId) {
        // Create a local message first for immediate UI feedback
        TLRPC.TL_message localMsg = new TLRPC.TL_message();
        localMsg.id = getUserConfig().getNewMessageId();
        localMsg.out = true;
        localMsg.unread = true;
        localMsg.from_id = new TLRPC.TL_peerUser();
        localMsg.from_id.user_id = getUserConfig().getClientUserId();
        localMsg.peer_id = new TLRPC.TL_peerUser();
        localMsg.peer_id.user_id = dialogId;
        localMsg.dialog_id = dialogId;
        localMsg.date = (int) (System.currentTimeMillis() / 1000);
        localMsg.message = text;
        localMsg.media = new TLRPC.TL_messageMediaEmpty();
        localMsg.flags = 768; // HAS_FROM_ID | HAS_MEDIA
        localMsg.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;

        // Build user dict
        HashMap<Long, TLRPC.User> userMap = new HashMap<>();
        TLRPC.User currentUser = getUserConfig().getCurrentUser();
        if (currentUser != null) {
            userMap.put(currentUser.id, currentUser);
        }
        TLRPC.User otherUser = getMessagesController().getUser(dialogId);
        if (otherUser != null) {
            userMap.put(otherUser.id, otherUser);
        }

        // Create MessageObject and update UI
        MessageObject messageObject = new MessageObject(currentAccount, localMsg, userMap, null, true, true);
        messageObject.messageOwner.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;

        ArrayList<MessageObject> objArr = new ArrayList<>();
        objArr.add(messageObject);
        getMessagesController().updateInterfaceWithMessages(dialogId, objArr, 0);
        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);

        // Send to backend
        BackendApiClient.sendMessage(token, conversationId, text, new BackendApiClient.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                // Mark as sent
                localMsg.send_state = 0;
                messageObject.messageOwner.send_state = 0;

                try {
                    JSONObject msgData = response.getJSONObject("message");
                    int serverMsgId = msgData.getInt("id");
                    localMsg.id = serverMsgId;
                } catch (JSONException e) {
                    // Keep local ID
                }

                // Update dialog's last message
                TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(dialogId);
                if (dialog != null) {
                    dialog.top_message = localMsg.id;
                    dialog.last_message_date = localMsg.date;
                }

                getNotificationCenter().postNotificationName(NotificationCenter.messageReceivedByServer, localMsg.id, localMsg.id, localMsg, dialogId, 0L, 0, false);
                getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
                getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_SEND_STATE);
            }

            @Override
            public void onError(int statusCode, String error) {
                localMsg.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
                messageObject.messageOwner.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
                getNotificationCenter().postNotificationName(NotificationCenter.messageSendError, localMsg.id);
                getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_SEND_STATE);
            }
        });
    }

    // ==================== CONTACTS LOADING ====================

    /**
     * Load contacts from the backend API.
     */
    public void loadContactsFromBackend() {
        String token = getToken();
        if (token == null) return;

        BackendApiClient.getContacts(token, new BackendApiClient.ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                try {
                    JSONArray contacts = response.getJSONArray("contacts");
                    processContacts(contacts);
                } catch (JSONException e) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("BackendMessagesManager: Failed to parse contacts", e);
                    }
                    finishContactsLoading();
                }
            }

            @Override
            public void onError(int statusCode, String error) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("BackendMessagesManager: Failed to load contacts: " + error);
                }
                finishContactsLoading();
            }
        });
    }

    private void processContacts(JSONArray contactsArr) throws JSONException {
        MessagesController controller = getMessagesController();
        ContactsController contactsController = getContactsController();

        ArrayList<TLRPC.TL_contact> contactsList = new ArrayList<>();
        ArrayList<TLRPC.User> users = new ArrayList<>();

        for (int i = 0; i < contactsArr.length(); i++) {
            JSONObject contact = contactsArr.getJSONObject(i);
            TLRPC.TL_user user = createTlrpcUser(contact);
            users.add(user);
            controller.putUser(user, true);

            TLRPC.TL_contact tlContact = new TLRPC.TL_contact();
            tlContact.user_id = user.id;
            tlContact.mutual = true;
            contactsList.add(tlContact);
        }

        // Set contacts in ContactsController
        contactsController.contacts = contactsList;
        contactsController.contactsDict = new java.util.concurrent.ConcurrentHashMap<>();
        for (TLRPC.TL_contact c : contactsList) {
            contactsController.contactsDict.put(c.user_id, c);
        }

        // Build sorted sections
        contactsController.sortedUsersMutualSectionsArray = new ArrayList<>();
        contactsController.sortedUsersSectionsArray = new ArrayList<>();
        contactsController.usersSectionsDict = new HashMap<>();
        contactsController.usersMutualSectionsDict = new HashMap<>();

        HashMap<String, ArrayList<TLRPC.TL_contact>> sectionDict = new HashMap<>();
        ArrayList<String> sortedSections = new ArrayList<>();

        for (TLRPC.TL_contact c : contactsList) {
            TLRPC.User u = controller.getUser(c.user_id);
            if (u == null) continue;
            String name = UserObject.getFirstName(u);
            String key = name.length() > 0 ? name.substring(0, 1).toUpperCase() : "#";
            ArrayList<TLRPC.TL_contact> section = sectionDict.get(key);
            if (section == null) {
                section = new ArrayList<>();
                sectionDict.put(key, section);
                sortedSections.add(key);
            }
            section.add(c);
        }

        java.util.Collections.sort(sortedSections);
        contactsController.sortedUsersSectionsArray = sortedSections;
        contactsController.usersSectionsDict = sectionDict;
        contactsController.sortedUsersMutualSectionsArray = new ArrayList<>(sortedSections);
        contactsController.usersMutualSectionsDict = new HashMap<>(sectionDict);

        finishContactsLoading();
    }

    private void finishContactsLoading() {
        ContactsController contactsController = getContactsController();
        contactsController.contactsLoaded = true;
        getNotificationCenter().postNotificationName(NotificationCenter.contactsDidLoad);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Create a TLRPC.TL_user from a backend user JSON object.
     */
    private TLRPC.TL_user createTlrpcUser(JSONObject userJson) throws JSONException {
        int backendId = userJson.getInt("id");
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = toTlrpcUserId(backendId);
        user.first_name = userJson.optString("first_name", "");
        user.last_name = userJson.optString("last_name", "");
        user.username = userJson.optString("username", "");
        user.phone = userJson.optString("phone", "");
        user.status = new TLRPC.TL_userStatusRecently();
        user.flags = 1 | 2 | 4 | 16; // HAS_ACCESS_HASH | HAS_FIRST_NAME | HAS_LAST_NAME | HAS_PHONE
        user.access_hash = backendId; // Use backend ID as access hash
        return user;
    }

    /**
     * Create a TLRPC.TL_message from a backend message JSON object.
     */
    private TLRPC.TL_message createTlrpcMessage(JSONObject msgJson, long dialogId) throws JSONException {
        int msgId = msgJson.getInt("id");
        int senderId = msgJson.getInt("sender_id");
        long senderTlrpcId = toTlrpcUserId(senderId);
        int myBackendId = getBackendUserId();
        boolean isOut = (senderId == myBackendId);

        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = msgId;
        msg.message = msgJson.optString("text", "");
        msg.date = parseDate(msgJson.optString("created_at", ""));
        msg.out = isOut;
        msg.unread = !msgJson.optBoolean("is_read", false) && !isOut;
        msg.from_id = new TLRPC.TL_peerUser();
        msg.from_id.user_id = senderTlrpcId;
        msg.peer_id = new TLRPC.TL_peerUser();
        msg.peer_id.user_id = dialogId;
        msg.dialog_id = dialogId;
        msg.media = new TLRPC.TL_messageMediaEmpty();
        msg.flags = 768; // HAS_FROM_ID | HAS_MEDIA
        if (isOut) {
            msg.flags |= 2; // FLAG_OUT
        }
        msg.send_state = 0; // Already sent
        msg.entities = new ArrayList<>();
        return msg;
    }

    /**
     * Parse ISO 8601 date string to Unix timestamp.
     * A new SimpleDateFormat instance is created per call to ensure thread safety.
     */
    private int parseDate(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) {
            return (int) (System.currentTimeMillis() / 1000);
        }
        try {
            // Parse ISO 8601 format: 2024-01-15T10:30:00
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date date = sdf.parse(isoDate);
            return date != null ? (int) (date.getTime() / 1000) : (int) (System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            return (int) (System.currentTimeMillis() / 1000);
        }
    }

    /**
     * Refresh dialogs from backend (called after sending a message, etc.).
     */
    public void refreshDialogs() {
        loadingDialogs = false;
        loadDialogsFromBackend();
    }
}
