# BusAudioAndroid（杭州公交报站语音库）

这是一个 Android 离线报站播放器项目，支持：

- 读取 `config/lines` 配置与线路
- 按 JSON 合成规则即时拼接报站语音
- 从加密 `pak` 中按需解密音频并播放
- 状态栏/锁屏播放控制

## 一、目录要求（非常重要）

以 `bb` 目录为工程根目录时，打包脚本默认从 **上一级目录** 读取资源：

- `../00concat`（中文站名音频）
- `../00concatEng`（英文站名音频，可选）
- `../template`（模板音频）
- `../00config`（JSON 合成规则）
- `../00lines`（线路 xlsx/txt）

示例结构：

```text
E:\bus_collection\
  ├─00concat\
  ├─00concatEng\
  ├─template\
  ├─00config\
  ├─00lines\
  └─bb\
```

## 二、推荐打包方式

使用新增脚本：

```bat
build_apk_portable.bat
```

脚本会：

1. 检查上级目录资源是否齐全  
2. 执行 `pack_assets.ps1` 生成加密 payload  
3. 调用 Gradle 构建 APK

输出 APK：

`app\build\outputs\apk\debug\app-debug.apk`

## 三、首次运行说明

- App 会在自身私有目录创建 `config/lines/packs`
- 若未检测到有效配置，会自动从内置 `seed_config.zip / seed_lines.zip` 解包
- 配置判定会忽略 `pack_manifest.json`，避免“只有 manifest 但没有模板配置”的空配置问题

## 四、常见问题

### 1）加载后没有配置可选

先检查 `../00config` 下是否有 JSON，并重新运行 `build_apk_portable.bat`。

### 2）合成提示模板缺失

确认：

- `../template` 存在且有音频
- `../00config` 的 `resources` 引用文件名与模板文件匹配

### 3）如何长期维护打包

即使会话过期，你也可以只靠这三个文件继续打包：

- `pack_assets.ps1`
- `build_apk_portable.bat`
- 本 README

