from datetime import datetime, timedelta, timezone
from functools import wraps

import jwt
from flask import current_app, request, jsonify

from models import User, db


def generate_token(user):
    """Generate JWT token for user."""
    payload = {
        "user_id": user.id,
        "username": user.username,
        "is_admin": user.is_admin,
        "exp": datetime.now(timezone.utc)
        + timedelta(seconds=current_app.config["JWT_ACCESS_TOKEN_EXPIRES"]),
        "iat": datetime.now(timezone.utc),
    }
    return jwt.encode(payload, current_app.config["SECRET_KEY"], algorithm="HS256")


def decode_token(token):
    """Decode and validate JWT token."""
    try:
        payload = jwt.decode(
            token, current_app.config["SECRET_KEY"], algorithms=["HS256"]
        )
        return payload
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None


def token_required(f):
    """Decorator to require valid JWT token."""

    @wraps(f)
    def decorated(*args, **kwargs):
        token = None
        auth_header = request.headers.get("Authorization")
        if auth_header and auth_header.startswith("Bearer "):
            token = auth_header.split(" ")[1]

        if not token:
            return jsonify({"error": "Token is missing"}), 401

        payload = decode_token(token)
        if payload is None:
            return jsonify({"error": "Token is invalid or expired"}), 401

        user = db.session.get(User, payload["user_id"])
        if user is None:
            return jsonify({"error": "User not found"}), 401

        if not user.is_active:
            return jsonify({"error": "User account is disabled"}), 403

        request.current_user = user
        return f(*args, **kwargs)

    return decorated


def admin_required(f):
    """Decorator to require admin privileges."""

    @wraps(f)
    @token_required
    def decorated(*args, **kwargs):
        if not request.current_user.is_admin:
            return jsonify({"error": "Admin access required"}), 403
        return f(*args, **kwargs)

    return decorated
