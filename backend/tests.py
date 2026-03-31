"""Tests for the backend management API."""

import json
import os
import sys
import tempfile
import unittest

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app import create_app
from config import Config
from models import db, User


class TestConfig(Config):
    """Test configuration using a temporary database."""

    TESTING = True
    SQLALCHEMY_DATABASE_URI = "sqlite:///:memory:"
    SECRET_KEY = "test-secret-key-with-enough-length-for-hs256"
    ADMIN_USERNAME = "admin"
    ADMIN_PASSWORD = "admin123"


class BaseTestCase(unittest.TestCase):
    """Base test case with common setup/teardown."""

    def setUp(self):
        self.app = create_app(TestConfig)
        self.client = self.app.test_client()
        self.app_context = self.app.app_context()
        self.app_context.push()

    def tearDown(self):
        db.session.remove()
        db.drop_all()
        self.app_context.pop()

    def get_admin_token(self):
        """Login as admin and return JWT token."""
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "admin", "password": "admin123"}),
            content_type="application/json",
        )
        data = json.loads(response.data)
        return data["token"]

    def create_test_user(self, token, username="testuser", password="test123456"):
        """Create a test user via admin API."""
        response = self.client.post(
            "/api/admin/users",
            data=json.dumps(
                {
                    "username": username,
                    "password": password,
                    "first_name": "Test",
                    "last_name": "User",
                    "phone": "+1234567890",
                }
            ),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        return json.loads(response.data)


class TestHealthCheck(BaseTestCase):
    """Test health check endpoint."""

    def test_health_check(self):
        response = self.client.get("/api/health")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["status"], "ok")

    def test_root_endpoint(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertIn("endpoints", data)
        self.assertEqual(data["name"], "Telegram Backend Management API")


class TestAuthLogin(BaseTestCase):
    """Test authentication login endpoint."""

    def test_admin_login_success(self):
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "admin", "password": "admin123"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertIn("token", data)
        self.assertIn("user", data)
        self.assertEqual(data["user"]["username"], "admin")
        self.assertTrue(data["user"]["is_admin"])

    def test_login_invalid_password(self):
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "admin", "password": "wrong"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 401)
        data = json.loads(response.data)
        self.assertIn("error", data)

    def test_login_invalid_username(self):
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "nonexistent", "password": "admin123"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 401)

    def test_login_missing_fields(self):
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "admin"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)

    def test_login_empty_body(self):
        response = self.client.post(
            "/api/auth/login", data="{}", content_type="application/json"
        )
        self.assertEqual(response.status_code, 400)

    def test_login_disabled_user(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        # Disable user
        self.client.put(
            "/api/admin/users/2",
            data=json.dumps({"is_active": False}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "testuser", "password": "test123456"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 403)


class TestAuthProfile(BaseTestCase):
    """Test auth profile endpoints."""

    def test_get_profile(self):
        token = self.get_admin_token()
        response = self.client.get(
            "/api/auth/profile", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["user"]["username"], "admin")

    def test_get_profile_no_token(self):
        response = self.client.get("/api/auth/profile")
        self.assertEqual(response.status_code, 401)

    def test_get_profile_invalid_token(self):
        response = self.client.get(
            "/api/auth/profile", headers={"Authorization": "Bearer invalid-token"}
        )
        self.assertEqual(response.status_code, 401)

    def test_update_profile(self):
        token = self.get_admin_token()
        response = self.client.put(
            "/api/auth/profile",
            data=json.dumps({"first_name": "NewName", "phone": "+9876543210"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["user"]["first_name"], "NewName")
        self.assertEqual(data["user"]["phone"], "+9876543210")

    def test_change_password(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        # Login as test user
        resp = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "testuser", "password": "test123456"}),
            content_type="application/json",
        )
        user_token = json.loads(resp.data)["token"]

        # Change password
        response = self.client.post(
            "/api/auth/change-password",
            data=json.dumps(
                {"old_password": "test123456", "new_password": "newpass123"}
            ),
            content_type="application/json",
            headers={"Authorization": f"Bearer {user_token}"},
        )
        self.assertEqual(response.status_code, 200)

        # Login with new password
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "testuser", "password": "newpass123"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)

    def test_change_password_wrong_old(self):
        token = self.get_admin_token()
        response = self.client.post(
            "/api/auth/change-password",
            data=json.dumps(
                {"old_password": "wrongpassword", "new_password": "newpass123"}
            ),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 401)

    def test_change_password_too_short(self):
        token = self.get_admin_token()
        response = self.client.post(
            "/api/auth/change-password",
            data=json.dumps({"old_password": "admin123", "new_password": "short"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 400)


class TestAdminUsers(BaseTestCase):
    """Test admin user management endpoints."""

    def test_create_user(self):
        token = self.get_admin_token()
        response = self.client.post(
            "/api/admin/users",
            data=json.dumps(
                {
                    "username": "newuser",
                    "password": "password123",
                    "first_name": "New",
                    "last_name": "User",
                }
            ),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 201)
        data = json.loads(response.data)
        self.assertEqual(data["user"]["username"], "newuser")
        self.assertEqual(data["user"]["first_name"], "New")

    def test_create_user_duplicate(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        response = self.client.post(
            "/api/admin/users",
            data=json.dumps({"username": "testuser", "password": "test123456"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 409)

    def test_create_user_short_username(self):
        token = self.get_admin_token()
        response = self.client.post(
            "/api/admin/users",
            data=json.dumps({"username": "ab", "password": "test123456"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 400)

    def test_create_user_short_password(self):
        token = self.get_admin_token()
        response = self.client.post(
            "/api/admin/users",
            data=json.dumps({"username": "newuser", "password": "short"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 400)

    def test_list_users(self):
        token = self.get_admin_token()
        self.create_test_user(token, "user1")
        self.create_test_user(token, "user2")
        response = self.client.get(
            "/api/admin/users", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["total"], 3)  # admin + 2 users
        self.assertEqual(len(data["users"]), 3)

    def test_list_users_pagination(self):
        token = self.get_admin_token()
        for i in range(5):
            self.create_test_user(token, f"user{i}", f"password{i}12")
        response = self.client.get(
            "/api/admin/users?page=1&per_page=2",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(len(data["users"]), 2)
        self.assertEqual(data["total"], 6)  # admin + 5 users
        self.assertEqual(data["pages"], 3)

    def test_get_user(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        response = self.client.get(
            "/api/admin/users/2", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["user"]["username"], "testuser")

    def test_get_user_not_found(self):
        token = self.get_admin_token()
        response = self.client.get(
            "/api/admin/users/999", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 404)

    def test_update_user(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        response = self.client.put(
            "/api/admin/users/2",
            data=json.dumps(
                {
                    "first_name": "Updated",
                    "last_name": "Name",
                    "is_admin": True,
                }
            ),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["user"]["first_name"], "Updated")
        self.assertEqual(data["user"]["last_name"], "Name")
        self.assertTrue(data["user"]["is_admin"])

    def test_update_user_password(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        # Admin resets user password
        self.client.put(
            "/api/admin/users/2",
            data=json.dumps({"password": "resetpass123"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {token}"},
        )
        # Login with new password
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "testuser", "password": "resetpass123"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)

    def test_delete_user(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        response = self.client.delete(
            "/api/admin/users/2", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 200)
        # Verify user is deleted
        response = self.client.get(
            "/api/admin/users/2", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 404)

    def test_delete_self(self):
        token = self.get_admin_token()
        response = self.client.delete(
            "/api/admin/users/1", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 400)

    def test_delete_nonexistent(self):
        token = self.get_admin_token()
        response = self.client.delete(
            "/api/admin/users/999", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 404)

    def test_non_admin_access(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        # Login as regular user
        resp = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "testuser", "password": "test123456"}),
            content_type="application/json",
        )
        user_token = json.loads(resp.data)["token"]
        # Try admin endpoint
        response = self.client.get(
            "/api/admin/users", headers={"Authorization": f"Bearer {user_token}"}
        )
        self.assertEqual(response.status_code, 403)

    def test_get_stats(self):
        token = self.get_admin_token()
        self.create_test_user(token)
        response = self.client.get(
            "/api/admin/stats", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["total_users"], 2)
        self.assertEqual(data["active_users"], 2)
        self.assertEqual(data["admin_users"], 1)


class TestUserLoginFlow(BaseTestCase):
    """Test the full user creation -> login flow (simulating Android app)."""

    def test_full_login_flow(self):
        """Test: Admin creates user -> User logs in -> User gets profile."""
        # 1. Admin login
        admin_token = self.get_admin_token()

        # 2. Admin creates a user
        result = self.create_test_user(admin_token)
        self.assertEqual(result["user"]["username"], "testuser")

        # 3. User logs in
        response = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "testuser", "password": "test123456"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        user_token = data["token"]
        self.assertEqual(data["user"]["first_name"], "Test")

        # 4. User gets profile
        response = self.client.get(
            "/api/auth/profile", headers={"Authorization": f"Bearer {user_token}"}
        )
        self.assertEqual(response.status_code, 200)
        profile = json.loads(response.data)
        self.assertEqual(profile["user"]["username"], "testuser")

        # 5. User updates profile
        response = self.client.put(
            "/api/auth/profile",
            data=json.dumps({"first_name": "UpdatedName"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {user_token}"},
        )
        self.assertEqual(response.status_code, 200)
        updated = json.loads(response.data)
        self.assertEqual(updated["user"]["first_name"], "UpdatedName")


class TestContacts(BaseTestCase):
    """Test contacts endpoint."""

    def test_list_contacts_empty(self):
        """Admin is the only user, contacts should be empty."""
        token = self.get_admin_token()
        response = self.client.get(
            "/api/contacts", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(len(data["contacts"]), 0)

    def test_list_contacts_with_users(self):
        token = self.get_admin_token()
        self.create_test_user(token, "alice", "password123")
        self.create_test_user(token, "bob", "password123")

        # Login as alice
        resp = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "alice", "password": "password123"}),
            content_type="application/json",
        )
        alice_token = json.loads(resp.data)["token"]

        response = self.client.get(
            "/api/contacts", headers={"Authorization": f"Bearer {alice_token}"}
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        # Alice should see admin and bob but not herself
        self.assertEqual(len(data["contacts"]), 2)
        usernames = [c["username"] for c in data["contacts"]]
        self.assertNotIn("alice", usernames)
        self.assertIn("bob", usernames)
        self.assertIn("admin", usernames)

    def test_list_contacts_no_token(self):
        response = self.client.get("/api/contacts")
        self.assertEqual(response.status_code, 401)


class TestConversations(BaseTestCase):
    """Test conversation endpoints."""

    def _setup_two_users(self):
        admin_token = self.get_admin_token()
        self.create_test_user(admin_token, "alice", "password123")
        self.create_test_user(admin_token, "bob", "password123")

        resp = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "alice", "password": "password123"}),
            content_type="application/json",
        )
        alice_token = json.loads(resp.data)["token"]

        resp = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "bob", "password": "password123"}),
            content_type="application/json",
        )
        bob_token = json.loads(resp.data)["token"]
        return alice_token, bob_token

    def test_create_conversation(self):
        alice_token, bob_token = self._setup_two_users()
        # Alice creates conversation with Bob (user id 3)
        response = self.client.post(
            "/api/conversations",
            data=json.dumps({"user_id": 3}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertIn("conversation", data)
        self.assertIsNotNone(data["conversation"]["id"])

    def test_create_conversation_with_self(self):
        alice_token, _ = self._setup_two_users()
        response = self.client.post(
            "/api/conversations",
            data=json.dumps({"user_id": 2}),  # alice's own id
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 400)

    def test_create_conversation_nonexistent_user(self):
        alice_token, _ = self._setup_two_users()
        response = self.client.post(
            "/api/conversations",
            data=json.dumps({"user_id": 999}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 404)

    def test_create_conversation_idempotent(self):
        """Creating the same conversation twice returns the same one."""
        alice_token, _ = self._setup_two_users()
        r1 = self.client.post(
            "/api/conversations",
            data=json.dumps({"user_id": 3}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        r2 = self.client.post(
            "/api/conversations",
            data=json.dumps({"user_id": 3}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        d1 = json.loads(r1.data)["conversation"]["id"]
        d2 = json.loads(r2.data)["conversation"]["id"]
        self.assertEqual(d1, d2)

    def test_list_conversations_empty(self):
        alice_token, _ = self._setup_two_users()
        response = self.client.get(
            "/api/conversations",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(len(data["conversations"]), 0)

    def test_list_conversations_with_messages(self):
        alice_token, bob_token = self._setup_two_users()
        # Create conversation
        resp = self.client.post(
            "/api/conversations",
            data=json.dumps({"user_id": 3}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        conv_id = json.loads(resp.data)["conversation"]["id"]

        # Send a message
        self.client.post(
            f"/api/conversations/{conv_id}/messages",
            data=json.dumps({"text": "Hello Bob!"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )

        # Both users should see the conversation
        for token in [alice_token, bob_token]:
            response = self.client.get(
                "/api/conversations",
                headers={"Authorization": f"Bearer {token}"},
            )
            self.assertEqual(response.status_code, 200)
            data = json.loads(response.data)
            self.assertEqual(len(data["conversations"]), 1)
            self.assertIsNotNone(data["conversations"][0]["last_message"])


class TestMessages(BaseTestCase):
    """Test message endpoints."""

    def _setup_conversation(self):
        admin_token = self.get_admin_token()
        self.create_test_user(admin_token, "alice", "password123")
        self.create_test_user(admin_token, "bob", "password123")

        resp = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "alice", "password": "password123"}),
            content_type="application/json",
        )
        alice_token = json.loads(resp.data)["token"]

        resp = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "bob", "password": "password123"}),
            content_type="application/json",
        )
        bob_token = json.loads(resp.data)["token"]

        resp = self.client.post(
            "/api/conversations",
            data=json.dumps({"user_id": 3}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        conv_id = json.loads(resp.data)["conversation"]["id"]
        return alice_token, bob_token, conv_id

    def test_send_message(self):
        alice_token, _, conv_id = self._setup_conversation()
        response = self.client.post(
            f"/api/conversations/{conv_id}/messages",
            data=json.dumps({"text": "Hello!"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 201)
        data = json.loads(response.data)
        self.assertEqual(data["message"]["text"], "Hello!")

    def test_send_empty_message(self):
        alice_token, _, conv_id = self._setup_conversation()
        response = self.client.post(
            f"/api/conversations/{conv_id}/messages",
            data=json.dumps({"text": ""}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 400)

    def test_send_message_whitespace_only(self):
        alice_token, _, conv_id = self._setup_conversation()
        response = self.client.post(
            f"/api/conversations/{conv_id}/messages",
            data=json.dumps({"text": "   "}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 400)

    def test_list_messages(self):
        alice_token, bob_token, conv_id = self._setup_conversation()
        # Send messages from both users
        self.client.post(
            f"/api/conversations/{conv_id}/messages",
            data=json.dumps({"text": "Hi Bob!"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.client.post(
            f"/api/conversations/{conv_id}/messages",
            data=json.dumps({"text": "Hi Alice!"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {bob_token}"},
        )

        # List messages
        response = self.client.get(
            f"/api/conversations/{conv_id}/messages",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["total"], 2)
        self.assertEqual(len(data["messages"]), 2)
        self.assertEqual(data["messages"][0]["text"], "Hi Bob!")
        self.assertEqual(data["messages"][1]["text"], "Hi Alice!")

    def test_list_messages_marks_as_read(self):
        alice_token, bob_token, conv_id = self._setup_conversation()
        # Alice sends a message
        self.client.post(
            f"/api/conversations/{conv_id}/messages",
            data=json.dumps({"text": "Hello!"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )

        # Bob reads the messages
        self.client.get(
            f"/api/conversations/{conv_id}/messages",
            headers={"Authorization": f"Bearer {bob_token}"},
        )

        # Check unread count for Bob is now 0
        response = self.client.get(
            "/api/conversations",
            headers={"Authorization": f"Bearer {bob_token}"},
        )
        data = json.loads(response.data)
        self.assertEqual(data["conversations"][0]["unread_count"], 0)

    def test_access_denied_other_conversation(self):
        alice_token, bob_token, conv_id = self._setup_conversation()
        # Create another user
        admin_token = self.get_admin_token()
        self.create_test_user(admin_token, "charlie", "password123")
        resp = self.client.post(
            "/api/auth/login",
            data=json.dumps({"username": "charlie", "password": "password123"}),
            content_type="application/json",
        )
        charlie_token = json.loads(resp.data)["token"]

        # Charlie tries to access alice-bob conversation
        response = self.client.get(
            f"/api/conversations/{conv_id}/messages",
            headers={"Authorization": f"Bearer {charlie_token}"},
        )
        self.assertEqual(response.status_code, 403)

    def test_message_in_nonexistent_conversation(self):
        alice_token, _, _ = self._setup_conversation()
        response = self.client.post(
            "/api/conversations/999/messages",
            data=json.dumps({"text": "Hello!"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        self.assertEqual(response.status_code, 404)

    def test_full_messaging_flow(self):
        """Test complete flow: create users -> create conv -> exchange messages."""
        alice_token, bob_token, conv_id = self._setup_conversation()

        # Alice sends 3 messages
        for text in ["Hello!", "How are you?", "Want to meet?"]:
            self.client.post(
                f"/api/conversations/{conv_id}/messages",
                data=json.dumps({"text": text}),
                content_type="application/json",
                headers={"Authorization": f"Bearer {alice_token}"},
            )

        # Bob checks unread count
        response = self.client.get(
            "/api/conversations",
            headers={"Authorization": f"Bearer {bob_token}"},
        )
        data = json.loads(response.data)
        self.assertEqual(data["conversations"][0]["unread_count"], 3)

        # Bob reads messages
        response = self.client.get(
            f"/api/conversations/{conv_id}/messages",
            headers={"Authorization": f"Bearer {bob_token}"},
        )
        data = json.loads(response.data)
        self.assertEqual(data["total"], 3)

        # Bob's unread count should be 0 now
        response = self.client.get(
            "/api/conversations",
            headers={"Authorization": f"Bearer {bob_token}"},
        )
        data = json.loads(response.data)
        self.assertEqual(data["conversations"][0]["unread_count"], 0)

        # Bob replies
        self.client.post(
            f"/api/conversations/{conv_id}/messages",
            data=json.dumps({"text": "Sure!"}),
            content_type="application/json",
            headers={"Authorization": f"Bearer {bob_token}"},
        )

        # Alice checks - should have 1 unread from bob
        response = self.client.get(
            "/api/conversations",
            headers={"Authorization": f"Bearer {alice_token}"},
        )
        data = json.loads(response.data)
        self.assertEqual(data["conversations"][0]["unread_count"], 1)
        self.assertEqual(data["conversations"][0]["last_message"]["text"], "Sure!")


if __name__ == "__main__":
    unittest.main()
