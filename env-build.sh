#!/bin/sh
# 构建环境（沙箱会话易失，工具链持久化在 /workspace；本文件不入库）
# 注意：本文件用可见文件名，避免隐藏文件不跨会话持久化的问题
export GRADLE_USER_HOME=/workspace/.gradle-home
export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.20+101
export ANDROID_HOME=/workspace/.android-sdk
export GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080 -Dhttp.nonProxyHosts=localhost|127.0.0.1"
export _JAVA_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080"
