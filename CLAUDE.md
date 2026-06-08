# bluetooth - 低功耗蓝牙调试工具

## 项目概述
这是一个低功耗蓝牙调试工具，具有蓝牙发现、厂商识别、数据模拟和数据同步等功能。

## 技术栈
- **开发语言**: Java/Kotlin
- **Hook 框架**: Xposed API
- **最低 API**: 93 (LSPosed)

## 环境要求
- Android 7.0+
- LSPosed 支持 API 93+

## 功能特性
- 蓝牙发现
- 厂商识别
- 数据模拟
- 数据同步 (WebDAV)

## 使用方法
1. 下载并安装最新版本
2. 在 LSPosed 中激活并勾选 `com.android.bluetooth`
3. 在首页搜索蓝牙并选择要模拟的蓝牙设备
4. 返回首页点击"模拟"开始

## 构建命令
```bash
./gradlew assembleRelease
```

## 许可证
GPL 3.0

## 作者
dreamncn
