from flask import Blueprint, request, jsonify

from auth import generate_token, token_required
from models import User

auth_bp = Blueprint("auth", __name__, url_prefix="/api/auth")


@auth_bp.route("/login", methods=["POST"])
def login():
    """User login endpoint.

    Expects JSON body: {"username": "...", "password": "..."}
    Returns JWT token and user profile on success.
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "Request body is required"}), 400

    username = data.get("username", "").strip()
    password = data.get("password", "")

    if not username or not password:
        return jsonify({"error": "Username and password are required"}), 400

    user = User.query.filter_by(username=username).first()
    if user is None or not user.check_password(password):
        return jsonify({"error": "Invalid username or password"}), 401

    if not user.is_active:
        return jsonify({"error": "Account is disabled"}), 403

    token = generate_token(user)
    return jsonify({"token": token, "user": user.to_dict()}), 200


@auth_bp.route("/profile", methods=["GET"])
@token_required
def get_profile():
    """Get current user profile."""
    return jsonify({"user": request.current_user.to_dict()}), 200


@auth_bp.route("/profile", methods=["PUT"])
@token_required
def update_profile():
    """Update current user profile.

    Allows updating: first_name, last_name, phone, avatar_url.
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "Request body is required"}), 400

    user = request.current_user
    if "first_name" in data:
        user.first_name = data["first_name"].strip()
    if "last_name" in data:
        user.last_name = data["last_name"].strip()
    if "phone" in data:
        user.phone = data["phone"].strip()
    if "avatar_url" in data:
        user.avatar_url = data["avatar_url"].strip()

    from models import db

    db.session.commit()
    return jsonify({"user": user.to_dict()}), 200


@auth_bp.route("/change-password", methods=["POST"])
@token_required
def change_password():
    """Change current user's password.

    Expects JSON body: {"old_password": "...", "new_password": "..."}
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "Request body is required"}), 400

    old_password = data.get("old_password", "")
    new_password = data.get("new_password", "")

    if not old_password or not new_password:
        return jsonify({"error": "Both old and new passwords are required"}), 400

    if len(new_password) < 6:
        return jsonify({"error": "New password must be at least 6 characters"}), 400

    user = request.current_user
    if not user.check_password(old_password):
        return jsonify({"error": "Current password is incorrect"}), 401

    user.set_password(new_password)
    from models import db

    db.session.commit()
    return jsonify({"message": "Password changed successfully"}), 200
