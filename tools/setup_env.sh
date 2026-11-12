#!/usr/bin/env bash
# 沙箱环境自举：Gradle 代理/镜像配置 + Android SDK 安装（容器重启后重跑即可，幂等）
# 依赖：mise 提供的 JDK17（基础镜像内置）
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA17=/root/.local/share/mise/installs/java/17.0.20+101
[ -d "$JAVA17" ] || { echo "缺少 JDK17（mise java@17）"; exit 1; }
export JAVA_HOME="$JAVA17"
export PATH="$JAVA_HOME/bin:$PATH"

# 1) ~/.gradle：代理 + 阿里云镜像（Maven Central 直连易 429 限流）+ 低并发
mkdir -p /root/.gradle
cat > /root/.gradle/gradle.properties <<'EOF'
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=18080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=18080
systemProp.http.nonProxyHosts=localhost|127.0.0.1
org.gradle.workers.max=2
EOF
cat > /root/.gradle/init.gradle.kts <<'EOF'
// 镜像加速：阿里云优先，官方源兜底（环境 workaround，不改动仓库文件）
settingsEvaluated {
    pluginManagement {
        repositories {
            clear()
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
    dependencyResolutionManagement {
        repositories {
            clear()
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            google()
            mavenCentral()
        }
    }
}
EOF

# 2) Android SDK（幂等：platforms/android-35 + build-tools/34.0.0 + platform-tools）
if [ ! -d /opt/android-sdk/platforms/android-35 ] || [ ! -d /opt/android-sdk/build-tools/34.0.0 ]; then
  echo "[*] 安装 Android SDK ..."
  export _JAVA_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080"
  curl -sL -o /tmp/cmdtools.zip --connect-timeout 15 \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf /opt/android-sdk
  mkdir -p /opt/android-sdk/cmdline-tools
  unzip -q /tmp/cmdtools.zip -d /opt/android-sdk/cmdline-tools
  mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
  yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true
  /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --install \
    "platforms;android-35" "build-tools;34.0.0" "platform-tools" >/dev/null
  rm -f /tmp/cmdtools.zip
fi

echo "[✓] 环境就绪 JAVA_HOME=$JAVA_HOME SDK=/opt/android-sdk"
