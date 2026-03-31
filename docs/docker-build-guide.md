# Telegram Android APK — Docker 自动化打包指南

## 目录

- [概述](#概述)
- [系统要求](#系统要求)
- [快速开始](#快速开始)
- [环境安装](#环境安装)
- [构建 APK](#构建-apk)
- [构建目标说明](#构建目标说明)
- [Docker Compose 用法](#docker-compose-用法)
- [高级配置](#高级配置)
- [项目构建架构分析](#项目构建架构分析)
- [常见问题排查](#常见问题排查)
- [文件说明](#文件说明)

---

## 概述

本方案提供完整的 Docker 容器化自动打包环境，用于在 Ubuntu 22.04 服务器上构建
Telegram Android APK / AAB。所有构建依赖 (Android SDK、NDK、Gradle、JDK) 均封装
在 Docker 镜像中，确保环境一致性和可复现性。

**特性:**

- ✅ 一键安装所有环境依赖 (Docker、Compose)
- ✅ 一键构建 APK / AAB
- ✅ 支持 6 种构建目标 (standalone / release / huawei / bundle / debug 等)
- ✅ Gradle 缓存持久化，加速增量构建
- ✅ Docker Compose 编排，简化操作
- ✅ 构建产物自动收集到宿主机

---

## 系统要求

| 项目 | 最低要求 | 推荐配置 |
|------|---------|---------|
| 操作系统 | Ubuntu 22.04 LTS | Ubuntu 22.04 LTS |
| CPU | 2 核 | 4+ 核 |
| 内存 | 4 GB | 8+ GB |
| 磁盘空间 | 30 GB | 50+ GB |
| 网络 | 需要互联网连接 | 稳定宽带 |

> ⚠️ 首次构建会下载约 2GB 的 Android SDK/NDK，Docker 镜像大小约 5-8GB。

---

## 快速开始

```bash
# 1. 安装环境 (仅需一次，需要 root 权限)
sudo ./scripts/setup-build-env.sh

# 2. 重新登录以使 docker 组生效
newgrp docker

# 3. 构建 Standalone APK (推荐首次测试)
./scripts/build-apk.sh standalone

# 4. 查看构建产物
ls -la build-output/
```

---

## 环境安装

### 自动安装 (推荐)

```bash
# 赋予执行权限
chmod +x scripts/setup-build-env.sh

# 执行安装 (需要 sudo)
sudo ./scripts/setup-build-env.sh
```

安装脚本会自动完成以下操作:

1. **系统检查** — 验证 Ubuntu 版本、磁盘空间、内存
2. **系统依赖** — 安装 curl、git、python3 等基础工具
3. **Docker Engine** — 从官方源安装 Docker CE
4. **Docker Compose** — 安装 Docker Compose 插件
5. **用户配置** — 将当前用户加入 docker 组
6. **系统调优** — 优化 inotify watches 和 memory map
7. **Docker 镜像** — 构建 `telegram-builder` 镜像 (含 Android SDK/NDK)

### 手动安装

如果需要手动安装环境:

```bash
# 1. 安装 Docker
curl -fsSL https://get.docker.com | bash

# 2. 启动 Docker
sudo systemctl enable docker
sudo systemctl start docker

# 3. 添加用户到 docker 组
sudo usermod -aG docker $USER
newgrp docker

# 4. 构建 Docker 镜像
cd /path/to/Telegram
docker build -f docker/Dockerfile.build -t telegram-builder .
```

---

## 构建 APK

### 基本用法

```bash
# 赋予执行权限 (仅需一次)
chmod +x scripts/build-apk.sh

# 构建 Standalone APK
./scripts/build-apk.sh standalone

# 构建 Release APK (Google Play)
./scripts/build-apk.sh release

# 构建所有变体
./scripts/build-apk.sh all
```

### 命令行选项

```
Usage: ./scripts/build-apk.sh [OPTIONS] <target>

Options:
  -o, --output DIR      输出目录 (默认: ./build-output)
  -t, --tag TAG         Docker 镜像标签 (默认: telegram-builder)
  -j, --jobs N          Gradle 并行 worker 数 (默认: 自动)
  -m, --memory SIZE     JVM 最大堆内存 (默认: 4096m)
  --no-cache            禁用 Gradle 缓存卷
  --rebuild-image       强制重建 Docker 镜像
  -h, --help            显示帮助信息
```

### 示例

```bash
# 指定输出目录
./scripts/build-apk.sh -o /data/apk-releases standalone

# 增大内存 (8GB RAM 服务器)
./scripts/build-apk.sh -m 6144m all

# 限制并行 worker (低配服务器)
./scripts/build-apk.sh -j 2 release

# 重建 Docker 镜像后构建
./scripts/build-apk.sh --rebuild-image release
```

---

## 构建目标说明

| 目标 | Gradle 任务 | 输出文件 | 说明 |
|------|------------|---------|------|
| `standalone` | `:TMessagesProj_AppStandalone:assembleAfatStandalone` | `app.apk` | 独立版 (无 Google Play 服务依赖) |
| `release` | `:TMessagesProj_App:assembleAfatRelease` | `app.apk` | Google Play 正式版 APK |
| `huawei` | `:TMessagesProj_AppHuawei:assembleAfatRelease` | `app-huawei.apk` | 华为应用市场版 |
| `bundle` | `:TMessagesProj_App:bundleBundleAfatRelease` | `*.aab` | Google Play AAB Bundle |
| `bundle23` | `:TMessagesProj_App:bundleBundleAfat_SDK23Release` | `*.aab` | AAB Bundle (SDK 23+) |
| `debug` | `:TMessagesProj_App:assembleAfatDebug` | `app.apk` | 调试版 APK |
| `all` | 以上全部 | 多个文件 | 构建所有变体 |

### 应用模块说明

| 模块 | 包名后缀 | 说明 |
|------|---------|------|
| `TMessagesProj` | — | 核心库 (共享代码) |
| `TMessagesProj_App` | `org.telegram.messenger` | 主 App (Google Play) |
| `TMessagesProj_AppStandalone` | `org.telegram.messenger.web` | 独立版 |
| `TMessagesProj_AppHuawei` | `org.telegram.messenger` | 华为版 |

### 版本信息 (来自 gradle.properties)

- **版本号**: 12.5.1 (APP_VERSION_NAME)
- **版本代码**: 6580 (APP_VERSION_CODE)
- **包名**: org.telegram.messenger (APP_PACKAGE)

---

## Docker Compose 用法

作为 `build-apk.sh` 的替代方案，也可以使用 Docker Compose:

```bash
# 构建 Standalone APK
docker compose -f docker/docker-compose.build.yml run --rm build-standalone

# 构建 Release APK
docker compose -f docker/docker-compose.build.yml run --rm build-release

# 构建 Debug APK
docker compose -f docker/docker-compose.build.yml run --rm build-debug

# 构建所有变体
docker compose -f docker/docker-compose.build.yml run --rm build-all
```

---

## 高级配置

### 签名配置

默认使用项目自带的调试签名 (`TMessagesProj/config/release.keystore`)。
如需使用自定义签名:

1. 将 keystore 文件放入项目目录
2. 创建 `local.properties`:

```properties
RELEASE_KEY_PASSWORD=your_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_STORE_PASSWORD=your_store_password
```

### Gradle 缓存

构建使用 Docker 持久化卷 `telegram-gradle-cache` 存储 Gradle 缓存。

```bash
# 查看缓存卷
docker volume inspect telegram-gradle-cache

# 清理缓存 (全量重新构建)
docker volume rm telegram-gradle-cache
```

### 性能调优

```bash
# 8GB+ RAM 服务器
./scripts/build-apk.sh -m 6144m -j 4 all

# 4GB RAM 服务器 (节约内存)
./scripts/build-apk.sh -m 2048m -j 1 standalone

# CI/CD 环境 (禁用缓存确保干净构建)
./scripts/build-apk.sh --no-cache --rebuild-image release
```

---

## 项目构建架构分析

### 技术栈

```
┌─────────────────────────────────────────────────┐
│                 Docker Container                │
│  ┌────────────────────────────────────────────┐ │
│  │  Gradle 8.7  +  JDK 17                    │ │
│  │  ┌──────────────────────────────────────┐  │ │
│  │  │  Android Gradle Plugin 8.6.1         │  │ │
│  │  │  ┌────────────────────────────────┐  │  │ │
│  │  │  │  Android SDK 35 (API 35)       │  │  │ │
│  │  │  │  Build Tools 35.0.0            │  │  │ │
│  │  │  │  NDK 21.4.7075529              │  │  │ │
│  │  │  │  CMake 3.10.2                  │  │  │ │
│  │  │  └────────────────────────────────┘  │  │ │
│  │  └──────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

### Native 代码 (JNI)

项目包含大量 C/C++ 原生代码，通过 NDK + CMake 编译:

- **BoringSSL** — TLS/加密
- **FFmpeg** — 音视频编解码
- **Opus** — 音频编解码
- **RLottie** — 动画渲染
- **SQLite** — 本地数据库
- **TgNet** — Telegram 网络协议
- **VoIP** — 语音通话
- **libvpx / dav1d** — VP9 / AV1 视频编解码

### 构建流程

```
源代码 → Docker 容器
    → Gradle 解析依赖
    → CMake/NDK 编译原生库 (C/C++ → .so)
    → Java/Kotlin 编译
    → 资源处理 + ProGuard 混淆
    → APK 签名
    → 产物输出到宿主机
```

---

## 常见问题排查

### 1. Docker 权限错误

```
ERROR: permission denied while trying to connect to the Docker daemon socket
```

**解决方案:**
```bash
sudo usermod -aG docker $USER
newgrp docker
# 或者重新登录
```

### 2. 磁盘空间不足

```
No space left on device
```

**解决方案:**
```bash
# 清理 Docker 缓存
docker system prune -a
docker volume prune

# 清理 Gradle 缓存
docker volume rm telegram-gradle-cache
```

### 3. 内存不足 (OOM)

```
java.lang.OutOfMemoryError: Java heap space
```

**解决方案:**
```bash
# 增大 JVM 堆内存
./scripts/build-apk.sh -m 6144m release

# 减少并行 worker
./scripts/build-apk.sh -j 1 -m 4096m release
```

### 4. NDK 编译失败

```
CMake Error: The source directory does not contain a CMakeLists.txt file
```

**解决方案:** 确保 `TMessagesProj/jni/CMakeLists.txt` 存在且完整。

### 5. 网络超时

```
Could not resolve com.android.tools.build:gradle
```

**解决方案:** 检查 Docker 容器的网络连接:
```bash
docker run --rm telegram-builder ping -c 3 dl.google.com
```

### 6. 首次构建很慢

首次构建需要:
- 下载所有 Gradle 依赖 (~500MB)
- 编译所有原生代码 (~10-20 分钟)
- 编译 Java 代码 (~5-10 分钟)

后续构建由于 Gradle 缓存会显著加速。

---

## 文件说明

```
Telegram/
├── docker/
│   ├── Dockerfile.build            # 构建环境 Docker 镜像
│   ├── docker-compose.build.yml    # Docker Compose 编排配置
│   └── docker-entrypoint.sh        # 容器入口脚本
├── scripts/
│   ├── setup-build-env.sh          # Ubuntu 22.04 环境安装脚本
│   └── build-apk.sh                # APK 构建脚本 (主脚本)
├── docs/
│   └── docker-build-guide.md       # 本文档
└── Dockerfile                      # 原始 Dockerfile (项目自带)
```

| 脚本 | 权限 | 用途 |
|------|------|------|
| `scripts/setup-build-env.sh` | `sudo` 执行 | 安装 Docker + 构建 Docker 镜像 |
| `scripts/build-apk.sh` | 普通用户 | 执行 APK/AAB 构建 |
| `docker/docker-entrypoint.sh` | 容器内使用 | Docker 容器入口点 |
