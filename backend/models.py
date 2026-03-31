import secrets
from datetime import datetime, timedelta, timezone

from flask_sqlalchemy import SQLAlchemy
import bcrypt

db = SQLAlchemy()


def _utcnow():
    return datetime.now(timezone.utc)


class VerificationCode(db.Model):
    """Stores phone verification codes for backend phone login."""

    __tablename__ = "verification_codes"

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    phone = db.Column(db.String(20), nullable=False, index=True)
    code = db.Column(db.String(10), nullable=False)
    is_used = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=_utcnow)
    expires_at = db.Column(db.DateTime, nullable=False)

    @staticmethod
    def generate(phone, code_length=5, expire_minutes=5):
        """Generate a new verification code for the given phone number.

        Invalidates any existing unused codes for this phone number first.
        """
        # Mark all existing unused codes for this phone as used
        VerificationCode.query.filter_by(phone=phone, is_used=False).update(
            {"is_used": True}
        )

        code = "".join([str(secrets.randbelow(10)) for _ in range(code_length)])
        vc = VerificationCode(
            phone=phone,
            code=code,
            expires_at=datetime.now(timezone.utc) + timedelta(minutes=expire_minutes),
        )
        db.session.add(vc)
        db.session.commit()
        return vc

    def is_valid(self):
        """Check if the code is still valid (not used, not expired)."""
        now = datetime.now(timezone.utc)
        expires = self.expires_at
        # Ensure both datetimes are timezone-aware for comparison
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=timezone.utc)
        return not self.is_used and now < expires

    def to_dict(self):
        return {
            "id": self.id,
            "phone": self.phone,
            "code": self.code,
            "is_used": self.is_used,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "expires_at": self.expires_at.isoformat() if self.expires_at else None,
        }

    def __repr__(self):
        return f"<VerificationCode {self.phone}: {self.code}>"


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
