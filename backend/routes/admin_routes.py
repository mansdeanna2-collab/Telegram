from flask import Blueprint, request, jsonify

from auth import admin_required
from models import User, db

admin_bp = Blueprint("admin", __name__, url_prefix="/api/admin")


@admin_bp.route("/users", methods=["GET"])
@admin_required
def list_users():
    """List all users with pagination.

    Query params: page (default 1), per_page (default 20)
    """
    page = request.args.get("page", 1, type=int)
    per_page = request.args.get("per_page", 20, type=int)
    per_page = min(per_page, 100)

    pagination = User.query.order_by(User.created_at.desc()).paginate(
        page=page, per_page=per_page, error_out=False
    )

    return jsonify(
        {
            "users": [u.to_dict() for u in pagination.items],
            "total": pagination.total,
            "page": pagination.page,
            "per_page": pagination.per_page,
            "pages": pagination.pages,
        }
    ), 200


@admin_bp.route("/users", methods=["POST"])
@admin_required
def create_user():
    """Create a new user.

    Expects JSON body: {
        "username": "...",
        "password": "...",
        "first_name": "...",    (optional)
        "last_name": "...",     (optional)
        "phone": "...",         (optional)
        "is_admin": false       (optional)
    }
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "Request body is required"}), 400

    username = data.get("username", "").strip()
    password = data.get("password", "")

    if not username or not password:
        return jsonify({"error": "Username and password are required"}), 400

    if len(username) < 3:
        return jsonify({"error": "Username must be at least 3 characters"}), 400

    if len(password) < 6:
        return jsonify({"error": "Password must be at least 6 characters"}), 400

    if User.query.filter_by(username=username).first():
        return jsonify({"error": "Username already exists"}), 409

    user = User(
        username=username,
        first_name=data.get("first_name", "").strip(),
        last_name=data.get("last_name", "").strip(),
        phone=data.get("phone", "").strip(),
        avatar_url=data.get("avatar_url", "").strip(),
        is_admin=data.get("is_admin", False),
        is_active=True,
    )
    user.set_password(password)

    db.session.add(user)
    db.session.commit()

    return jsonify({"user": user.to_dict(), "message": "User created successfully"}), 201


@admin_bp.route("/users/<int:user_id>", methods=["GET"])
@admin_required
def get_user(user_id):
    """Get user details by ID."""
    user = User.query.get(user_id)
    if user is None:
        return jsonify({"error": "User not found"}), 404
    return jsonify({"user": user.to_dict()}), 200


@admin_bp.route("/users/<int:user_id>", methods=["PUT"])
@admin_required
def update_user(user_id):
    """Update user details.

    Allows updating: username, first_name, last_name, phone, avatar_url,
                     is_admin, is_active, password
    """
    user = User.query.get(user_id)
    if user is None:
        return jsonify({"error": "User not found"}), 404

    data = request.get_json()
    if not data:
        return jsonify({"error": "Request body is required"}), 400

    if "username" in data:
        new_username = data["username"].strip()
        if new_username != user.username:
            if len(new_username) < 3:
                return jsonify(
                    {"error": "Username must be at least 3 characters"}
                ), 400
            if User.query.filter_by(username=new_username).first():
                return jsonify({"error": "Username already exists"}), 409
            user.username = new_username

    if "first_name" in data:
        user.first_name = data["first_name"].strip()
    if "last_name" in data:
        user.last_name = data["last_name"].strip()
    if "phone" in data:
        user.phone = data["phone"].strip()
    if "avatar_url" in data:
        user.avatar_url = data["avatar_url"].strip()
    if "is_admin" in data:
        user.is_admin = bool(data["is_admin"])
    if "is_active" in data:
        user.is_active = bool(data["is_active"])
    if "password" in data and data["password"]:
        if len(data["password"]) < 6:
            return jsonify({"error": "Password must be at least 6 characters"}), 400
        user.set_password(data["password"])

    db.session.commit()
    return jsonify({"user": user.to_dict(), "message": "User updated successfully"}), 200


@admin_bp.route("/users/<int:user_id>", methods=["DELETE"])
@admin_required
def delete_user(user_id):
    """Delete a user by ID."""
    user = User.query.get(user_id)
    if user is None:
        return jsonify({"error": "User not found"}), 404

    if user.id == request.current_user.id:
        return jsonify({"error": "Cannot delete your own account"}), 400

    db.session.delete(user)
    db.session.commit()
    return jsonify({"message": "User deleted successfully"}), 200


@admin_bp.route("/stats", methods=["GET"])
@admin_required
def get_stats():
    """Get system statistics."""
    total_users = User.query.count()
    active_users = User.query.filter_by(is_active=True).count()
    admin_users = User.query.filter_by(is_admin=True).count()

    return jsonify(
        {
            "total_users": total_users,
            "active_users": active_users,
            "admin_users": admin_users,
        }
    ), 200
