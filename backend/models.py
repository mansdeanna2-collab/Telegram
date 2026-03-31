from datetime import datetime, timezone

from flask_sqlalchemy import SQLAlchemy
import bcrypt

db = SQLAlchemy()


def _utcnow():
    return datetime.now(timezone.utc)


class User(db.Model):
    __tablename__ = "users"

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    username = db.Column(db.String(80), unique=True, nullable=False, index=True)
    password_hash = db.Column(db.String(128), nullable=False)
    first_name = db.Column(db.String(80), nullable=False, default="")
    last_name = db.Column(db.String(80), nullable=False, default="")
    phone = db.Column(db.String(20), nullable=True, default="")
    avatar_url = db.Column(db.String(256), nullable=True, default="")
    is_admin = db.Column(db.Boolean, default=False)
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.DateTime, default=_utcnow)
    updated_at = db.Column(db.DateTime, default=_utcnow, onupdate=_utcnow)

    def set_password(self, password):
        self.password_hash = bcrypt.hashpw(
            password.encode("utf-8"), bcrypt.gensalt()
        ).decode("utf-8")

    def check_password(self, password):
        return bcrypt.checkpw(
            password.encode("utf-8"), self.password_hash.encode("utf-8")
        )

    def to_dict(self, include_sensitive=False):
        data = {
            "id": self.id,
            "username": self.username,
            "first_name": self.first_name,
            "last_name": self.last_name,
            "phone": self.phone,
            "avatar_url": self.avatar_url,
            "is_admin": self.is_admin,
            "is_active": self.is_active,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }
        return data

    def __repr__(self):
        return f"<User {self.username}>"


class Conversation(db.Model):
    __tablename__ = "conversations"

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    user1_id = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=False)
    user2_id = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=False)
    created_at = db.Column(db.DateTime, default=_utcnow)
    updated_at = db.Column(db.DateTime, default=_utcnow, onupdate=_utcnow)

    user1 = db.relationship("User", foreign_keys=[user1_id])
    user2 = db.relationship("User", foreign_keys=[user2_id])
    messages = db.relationship(
        "Message", back_populates="conversation", order_by="Message.created_at"
    )

    __table_args__ = (
        db.UniqueConstraint("user1_id", "user2_id", name="uq_conversation_pair"),
    )

    def other_user(self, current_user_id):
        """Return the other participant in the conversation."""
        if self.user1_id == current_user_id:
            return self.user2
        return self.user1

    def to_dict(self, current_user_id=None):
        other = self.other_user(current_user_id) if current_user_id else None
        last_msg = (
            Message.query.filter_by(conversation_id=self.id)
            .order_by(Message.created_at.desc())
            .first()
        )
        unread = (
            Message.query.filter_by(
                conversation_id=self.id, is_read=False
            )
            .filter(Message.sender_id != current_user_id)
            .count()
            if current_user_id
            else 0
        )
        data = {
            "id": self.id,
            "user1_id": self.user1_id,
            "user2_id": self.user2_id,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
            "unread_count": unread,
        }
        if other:
            data["other_user"] = other.to_dict()
        if last_msg:
            data["last_message"] = last_msg.to_dict()
        else:
            data["last_message"] = None
        return data

    def __repr__(self):
        return f"<Conversation {self.id}: {self.user1_id} <-> {self.user2_id}>"


class Message(db.Model):
    __tablename__ = "messages"

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    conversation_id = db.Column(
        db.Integer, db.ForeignKey("conversations.id"), nullable=False, index=True
    )
    sender_id = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=False)
    text = db.Column(db.Text, nullable=False)
    is_read = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=_utcnow)

    conversation = db.relationship("Conversation", back_populates="messages")
    sender = db.relationship("User", foreign_keys=[sender_id])

    def to_dict(self):
        return {
            "id": self.id,
            "conversation_id": self.conversation_id,
            "sender_id": self.sender_id,
            "text": self.text,
            "is_read": self.is_read,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }

    def __repr__(self):
        return f"<Message {self.id} in conv {self.conversation_id}>"
