## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It's superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Project Structure

```
├── TMessagesProj/              # Core Android library (main source code)
├── TMessagesProj_App/          # Google Play Store build variant
├── TMessagesProj_AppStandalone/# Standalone APK build variant
├── TMessagesProj_AppHuawei/    # Huawei AppGallery build variant
├── TMessagesProj_AppHockeyApp/ # HockeyApp distribution variant
├── TMessagesProj_AppTests/     # Test module
├── backend/                    # Backend management system (Python Flask)
├── scripts/                    # Build scripts
├── docker/                     # Docker configuration
└── docs/                       # Documentation
```

## Backend Management System

This project includes a custom backend management system that allows administrators to manage users who can log into the Android app via username/password authentication.

### Backend Features

- **User Management**: Create, read, update, and delete users via REST API
- **JWT Authentication**: Secure token-based authentication
- **Admin Panel API**: Admin-only endpoints for user management
- **SQLite Database**: Lightweight embedded database
- **Password Security**: bcrypt password hashing
- **CORS Support**: Cross-origin resource sharing for mobile app communication

### Quick Start - Backend

**Requirements**: Python 3.8+

```bash
cd backend
chmod +x run.sh
./run.sh
```

Or manually:

```bash
cd backend
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python app.py
```

The server starts at `http://localhost:5000`. A default admin account is created automatically:
- **Username**: `admin`
- **Password**: `admin123`

### Backend API Endpoints

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/health` | Health check | None |
| POST | `/api/auth/login` | User login | None |
| GET | `/api/auth/profile` | Get current user profile | JWT |
| PUT | `/api/auth/profile` | Update current user profile | JWT |
| POST | `/api/auth/change-password` | Change password | JWT |
| GET | `/api/admin/users` | List all users (paginated) | Admin |
| POST | `/api/admin/users` | Create a new user | Admin |
| GET | `/api/admin/users/<id>` | Get user details | Admin |
| PUT | `/api/admin/users/<id>` | Update user | Admin |
| DELETE | `/api/admin/users/<id>` | Delete user | Admin |
| GET | `/api/admin/stats` | System statistics | Admin |

### API Usage Examples

**Admin Login:**
```bash
curl -X POST http://localhost:5000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}'
```

**Create a User (Admin):**
```bash
curl -X POST http://localhost:5000/api/admin/users \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <admin_token>" \
  -d '{
    "username": "newuser",
    "password": "password123",
    "first_name": "John",
    "last_name": "Doe",
    "phone": "+1234567890"
  }'
```

**User Login:**
```bash
curl -X POST http://localhost:5000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "newuser", "password": "password123"}'
```

### Backend Configuration

Environment variables (can also be set in a `.env` file):

| Variable | Default | Description |
|----------|---------|-------------|
| `SECRET_KEY` | (auto-generated) | JWT signing key |
| `DATABASE_URL` | `sqlite:///data/app.db` | Database connection URL |
| `JWT_ACCESS_TOKEN_EXPIRES` | `86400` | Token expiry in seconds (24h) |
| `ADMIN_USERNAME` | `admin` | Default admin username |
| `ADMIN_PASSWORD` | `admin123` | Default admin password |
| `HOST` | `0.0.0.0` | Server host |
| `PORT` | `5000` | Server port |

### Running Backend Tests

```bash
cd backend
source venv/bin/activate
python -m unittest tests -v
```

### Android App Integration

The Android app supports two login modes:

1. **Telegram Login** (default): Standard phone-based login via Telegram's MTProto protocol
2. **Backend Login**: Username/password login via the custom backend server

When launching the app, users can choose "Backend Login" from the intro screen to authenticate against the custom backend. The integration is handled by:

- `BackendConfig.java` - Backend server URL and session configuration
- `BackendApiClient.java` - HTTP client for backend communication
- `BackendLoginActivity.java` - Username/password login interface

**For Android Emulator**: The default backend URL `http://10.0.2.2:5000` points to the host machine's `localhost:5000`. For physical devices, change the server URL in the login screen to the actual server IP address.

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore, google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

The following custom API credentials have been configured in `BuildVars.java`:

| Variable | Value |
|----------|-------|
| `APP_ID` (api_id) | `35456655` |
| `APP_HASH` (api_hash) | `240cc26507b22aced7b4806649c6ba7d` |

You will require Android Studio 3.4, Android NDK rev. 20 and Android SDK 8.1

1. Download the Telegram source code from https://github.com/DrKLO/Telegram ( git clone https://github.com/DrKLO/Telegram.git )
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your release.keystore
4. Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there's a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
