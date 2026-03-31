import os

from flask import Flask, jsonify
from flask_cors import CORS

from config import Config
from models import db, User


def create_app(config_class=Config):
    app = Flask(__name__)
    app.config.from_object(config_class)

    # Enable CORS for Android app communication
    CORS(app, resources={r"/api/*": {"origins": "*"}})

    # Ensure data directory exists
    data_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
    os.makedirs(data_dir, exist_ok=True)

    # Initialize database
    db.init_app(app)

    with app.app_context():
        db.create_all()
        _create_default_admin(app)

    # Register blueprints
    from routes.auth_routes import auth_bp
    from routes.admin_routes import admin_bp

    app.register_blueprint(auth_bp)
    app.register_blueprint(admin_bp)

    # Health check endpoint
    @app.route("/api/health", methods=["GET"])
    def health_check():
        return jsonify({"status": "ok", "message": "Backend is running"}), 200

    # Root endpoint
    @app.route("/", methods=["GET"])
    def index():
        return jsonify(
            {
                "name": "Telegram Backend Management API",
                "version": "1.0.0",
                "endpoints": {
                    "health": "GET /api/health",
                    "auth_login": "POST /api/auth/login",
                    "auth_profile": "GET /api/auth/profile",
                    "auth_update_profile": "PUT /api/auth/profile",
                    "auth_change_password": "POST /api/auth/change-password",
                    "admin_list_users": "GET /api/admin/users",
                    "admin_create_user": "POST /api/admin/users",
                    "admin_get_user": "GET /api/admin/users/<id>",
                    "admin_update_user": "PUT /api/admin/users/<id>",
                    "admin_delete_user": "DELETE /api/admin/users/<id>",
                    "admin_stats": "GET /api/admin/stats",
                },
            }
        ), 200

    return app


def _create_default_admin(app):
    """Create default admin user if not exists."""
    admin = User.query.filter_by(username=app.config["ADMIN_USERNAME"]).first()
    if admin is None:
        admin = User(
            username=app.config["ADMIN_USERNAME"],
            first_name="System",
            last_name="Admin",
            is_admin=True,
            is_active=True,
        )
        admin.set_password(app.config["ADMIN_PASSWORD"])
        db.session.add(admin)
        db.session.commit()
        print(
            f"Default admin user created: {app.config['ADMIN_USERNAME']} / {app.config['ADMIN_PASSWORD']}"
        )


if __name__ == "__main__":
    app = create_app()
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", 5000))
    debug = os.environ.get("FLASK_DEBUG", "true").lower() == "true"
    app.run(host=host, port=port, debug=debug)
