"""Messaging API routes for conversations and messages."""

from flask import Blueprint, request, jsonify

from auth import token_required
from models import db, User, Conversation, Message

messaging_bp = Blueprint("messaging", __name__, url_prefix="/api")

MAX_MESSAGE_LENGTH = 4096


def _get_or_create_conversation(user1_id, user2_id):
    """Get existing conversation or create a new one.

    Always stores the smaller user id as user1_id for consistency.
    """
    uid_low = min(user1_id, user2_id)
    uid_high = max(user1_id, user2_id)
    conv = Conversation.query.filter_by(user1_id=uid_low, user2_id=uid_high).first()
    if conv is None:
        conv = Conversation(user1_id=uid_low, user2_id=uid_high)
        db.session.add(conv)
        db.session.commit()
    return conv


@messaging_bp.route("/contacts", methods=["GET"])
@token_required
def list_contacts():
    """List all active users except the current user (as contacts)."""
    current_user = request.current_user
    users = (
        User.query.filter(User.id != current_user.id, User.is_active == True)
        .order_by(User.first_name)
        .all()
    )
    return jsonify({"contacts": [u.to_dict() for u in users]}), 200


@messaging_bp.route("/conversations", methods=["GET"])
@token_required
def list_conversations():
    """List all conversations for the current user, ordered by last activity."""
    current_user = request.current_user
    conversations = (
        Conversation.query.filter(
            db.or_(
                Conversation.user1_id == current_user.id,
                Conversation.user2_id == current_user.id,
            )
        )
        .order_by(Conversation.updated_at.desc())
        .all()
    )
    return (
        jsonify(
            {
                "conversations": [
                    c.to_dict(current_user_id=current_user.id) for c in conversations
                ]
            }
        ),
        200,
    )


@messaging_bp.route("/conversations", methods=["POST"])
@token_required
def create_conversation():
    """Create or get a conversation with another user."""
    current_user = request.current_user
    data = request.get_json()
    if not data or "user_id" not in data:
        return jsonify({"error": "user_id is required"}), 400

    other_user_id = data["user_id"]
    if other_user_id == current_user.id:
        return jsonify({"error": "Cannot create conversation with yourself"}), 400

    other_user = db.session.get(User, other_user_id)
    if other_user is None or not other_user.is_active:
        return jsonify({"error": "User not found"}), 404

    conv = _get_or_create_conversation(current_user.id, other_user_id)
    return jsonify({"conversation": conv.to_dict(current_user_id=current_user.id)}), 200


@messaging_bp.route("/conversations/<int:conversation_id>/messages", methods=["GET"])
@token_required
def list_messages(conversation_id):
    """List messages in a conversation with pagination."""
    current_user = request.current_user
    conv = db.session.get(Conversation, conversation_id)
    if conv is None:
        return jsonify({"error": "Conversation not found"}), 404

    if current_user.id not in (conv.user1_id, conv.user2_id):
        return jsonify({"error": "Access denied"}), 403

    page = request.args.get("page", 1, type=int)
    per_page = request.args.get("per_page", 50, type=int)
    per_page = min(per_page, 100)

    pagination = (
        Message.query.filter_by(conversation_id=conversation_id)
        .order_by(Message.created_at.desc())
        .paginate(page=page, per_page=per_page, error_out=False)
    )

    # Mark unread messages from the other user as read
    Message.query.filter_by(conversation_id=conversation_id, is_read=False).filter(
        Message.sender_id != current_user.id
    ).update({"is_read": True})
    db.session.commit()

    return (
        jsonify(
            {
                "messages": [m.to_dict() for m in reversed(pagination.items)],
                "total": pagination.total,
                "page": page,
                "pages": pagination.pages,
            }
        ),
        200,
    )


@messaging_bp.route("/conversations/<int:conversation_id>/messages", methods=["POST"])
@token_required
def send_message(conversation_id):
    """Send a message in a conversation."""
    current_user = request.current_user
    conv = db.session.get(Conversation, conversation_id)
    if conv is None:
        return jsonify({"error": "Conversation not found"}), 404

    if current_user.id not in (conv.user1_id, conv.user2_id):
        return jsonify({"error": "Access denied"}), 403

    data = request.get_json()
    if not data or not data.get("text", "").strip():
        return jsonify({"error": "Message text is required"}), 400

    text = data["text"].strip()
    if len(text) > MAX_MESSAGE_LENGTH:
        return jsonify({"error": f"Message too long (max {MAX_MESSAGE_LENGTH} characters)"}), 400

    msg = Message(
        conversation_id=conversation_id,
        sender_id=current_user.id,
        text=text,
    )
    db.session.add(msg)
    # Explicitly update conversation timestamp since onupdate only triggers
    # when the Conversation row's own columns are modified via ORM, not
    # when a child Message is added.
    conv.updated_at = db.func.now()
    db.session.commit()

    return jsonify({"message": msg.to_dict()}), 201
