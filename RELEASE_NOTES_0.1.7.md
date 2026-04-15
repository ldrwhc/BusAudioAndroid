# BusAudioAndroid v0.1.7

## 更新内容

1. 修复首次初始化配置判定问题  
现在会忽略 `pack_manifest.json`，避免“配置目录看起来有 json 但实际上没有可用模板配置”导致的空配置问题。

2. 修复状态栏下一首按钮偶发消失  
在聚合播放模式下，补充了通知控制命令可用性的统一逻辑，避免临近片段尾部时被系统错误隐藏。

3. 版本号更新  
- `versionCode`: 8  
- `versionName`: 0.1.7

4. 新增打包文档与脚本  
- `README.md`（中文使用说明）  
- `build_apk_portable.bat`（可长期复用，默认绑定上级目录 `../00concat` 等资源）

## 运行与打包

在工程目录执行：

```bat
build_apk_portable.bat
```

输出 APK：

`app\build\outputs\apk\debug\app-debug.apk`

