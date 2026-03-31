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

    def create_test_user(self, token, username="testuser", password="test123456", phone="+1234567890"):
        """Create a test user via admin API."""
        response = self.client.post(
            "/api/admin/users",
            data=json.dumps(
                {
                    "username": username,
                    "password": password,
                    "first_name": "Test",
                    "last_name": "User",
                    "phone": phone,
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


class TestPhoneVerification(BaseTestCase):
    """Test phone-based verification code login flow."""

    def _create_user_with_phone(self, token, phone="+1234567890"):
        """Create a test user with a phone number."""
        return self.create_test_user(token, phone=phone)

    def test_send_code_success(self):
        """Test sending a verification code for a valid phone number."""
        token = self.get_admin_token()
        self._create_user_with_phone(token)

        response = self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertIn("message", data)
        self.assertEqual(data["phone"], "+1234567890")
        self.assertIn("expires_in_seconds", data)

    def test_send_code_missing_phone(self):
        """Test sending code without phone number."""
        response = self.client.post(
            "/api/auth/send-code",
            data=json.dumps({}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)

    def test_send_code_unknown_phone(self):
        """Test sending code for a phone not in the system."""
        response = self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+9999999999"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 404)

    def test_send_code_no_body(self):
        """Test sending code with no request body."""
        response = self.client.post(
            "/api/auth/send-code",
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)

    def test_verify_code_success(self):
        """Test full flow: send code then verify it."""
        token = self.get_admin_token()
        self._create_user_with_phone(token)

        # Send code
        self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )

        # Get the code from the database directly
        from models import VerificationCode

        vc = VerificationCode.query.filter_by(
            phone="+1234567890", is_used=False
        ).first()
        self.assertIsNotNone(vc)

        # Verify code
        response = self.client.post(
            "/api/auth/verify-code",
            data=json.dumps({"phone": "+1234567890", "code": vc.code}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertIn("token", data)
        self.assertIn("user", data)
        self.assertEqual(data["user"]["phone"], "+1234567890")
        self.assertEqual(data["user"]["username"], "testuser")

    def test_verify_code_wrong_code(self):
        """Test verifying with a wrong code."""
        token = self.get_admin_token()
        self._create_user_with_phone(token)

        # Send code
        self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )

        # Verify with wrong code
        response = self.client.post(
            "/api/auth/verify-code",
            data=json.dumps({"phone": "+1234567890", "code": "00000"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 401)
        data = json.loads(response.data)
        self.assertIn("error", data)

    def test_verify_code_no_pending_code(self):
        """Test verifying when no code has been sent."""
        response = self.client.post(
            "/api/auth/verify-code",
            data=json.dumps({"phone": "+1234567890", "code": "12345"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 404)

    def test_verify_code_missing_fields(self):
        """Test verifying with missing fields."""
        response = self.client.post(
            "/api/auth/verify-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 400)

    def test_verify_code_expired(self):
        """Test verifying an expired code."""
        from datetime import datetime, timedelta, timezone

        token = self.get_admin_token()
        self._create_user_with_phone(token)

        # Send code
        self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )

        # Manually expire the code
        from models import VerificationCode

        vc = VerificationCode.query.filter_by(
            phone="+1234567890", is_used=False
        ).first()
        vc.expires_at = datetime.now(timezone.utc) - timedelta(minutes=1)
        db.session.commit()

        # Verify the expired code
        response = self.client.post(
            "/api/auth/verify-code",
            data=json.dumps({"phone": "+1234567890", "code": vc.code}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 410)

    def test_verify_code_already_used(self):
        """Test verifying a code that was already used."""
        token = self.get_admin_token()
        self._create_user_with_phone(token)

        # Send code
        self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )

        from models import VerificationCode

        vc = VerificationCode.query.filter_by(
            phone="+1234567890", is_used=False
        ).first()
        code_value = vc.code

        # Verify first time (success)
        response = self.client.post(
            "/api/auth/verify-code",
            data=json.dumps({"phone": "+1234567890", "code": code_value}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)

        # Verify again with the same code (should fail)
        response = self.client.post(
            "/api/auth/verify-code",
            data=json.dumps({"phone": "+1234567890", "code": code_value}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 404)

    def test_send_code_invalidates_previous(self):
        """Test that sending a new code invalidates the previous one."""
        token = self.get_admin_token()
        self._create_user_with_phone(token)

        # Send first code
        self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )

        from models import VerificationCode

        first_vc = VerificationCode.query.filter_by(
            phone="+1234567890", is_used=False
        ).first()
        first_code = first_vc.code

        # Send second code
        self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )

        # First code should now be marked as used
        first_vc_refreshed = db.session.get(VerificationCode, first_vc.id)
        self.assertTrue(first_vc_refreshed.is_used)

        # New code should exist and be a different record
        new_vc = VerificationCode.query.filter_by(
            phone="+1234567890", is_used=False
        ).first()
        self.assertIsNotNone(new_vc)
        self.assertNotEqual(first_vc.id, new_vc.id)

    def test_admin_view_verification_codes(self):
        """Test that admin can view pending verification codes."""
        token = self.get_admin_token()
        self._create_user_with_phone(token)

        # Send code
        self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )

        # Admin views codes
        response = self.client.get(
            "/api/admin/verification-codes",
            headers={"Authorization": f"Bearer {token}"},
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertEqual(data["total"], 1)
        self.assertEqual(data["codes"][0]["phone"], "+1234567890")
        self.assertIn("code", data["codes"][0])

    def test_full_phone_login_flow(self):
        """Test the complete phone login flow: admin creates user -> send code -> admin sees code -> verify code."""
        # 1. Admin login
        admin_token = self.get_admin_token()

        # 2. Admin creates user with phone number
        self._create_user_with_phone(admin_token)

        # 3. Phone user requests code
        response = self.client.post(
            "/api/auth/send-code",
            data=json.dumps({"phone": "+1234567890"}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)

        # 4. Admin views the verification code
        response = self.client.get(
            "/api/admin/verification-codes",
            headers={"Authorization": f"Bearer {admin_token}"},
        )
        self.assertEqual(response.status_code, 200)
        codes_data = json.loads(response.data)
        self.assertEqual(codes_data["total"], 1)
        the_code = codes_data["codes"][0]["code"]

        # 5. Phone user verifies with the code
        response = self.client.post(
            "/api/auth/verify-code",
            data=json.dumps({"phone": "+1234567890", "code": the_code}),
            content_type="application/json",
        )
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.data)
        self.assertIn("token", data)
        user_token = data["token"]

        # 6. User can access their profile
        response = self.client.get(
            "/api/auth/profile",
            headers={"Authorization": f"Bearer {user_token}"},
        )
        self.assertEqual(response.status_code, 200)
        profile = json.loads(response.data)
        self.assertEqual(profile["user"]["username"], "testuser")


if __name__ == "__main__":
    unittest.main()
