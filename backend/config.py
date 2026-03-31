import os
import secrets

BASE_DIR = os.path.abspath(os.path.dirname(__file__))


class Config:
    SECRET_KEY = os.environ.get("SECRET_KEY") or secrets.token_hex(32)
    SQLALCHEMY_DATABASE_URI = os.environ.get(
        "DATABASE_URL", "sqlite:///" + os.path.join(BASE_DIR, "data", "app.db")
    )
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    JWT_ACCESS_TOKEN_EXPIRES = int(
        os.environ.get("JWT_ACCESS_TOKEN_EXPIRES", 86400)
    )  # 24 hours
    ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
    ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "admin123")
