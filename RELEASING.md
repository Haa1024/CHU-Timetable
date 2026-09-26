# 发版与更新清单

> 这份面向**维护者**。想看这个 App 是什么、怎么用，请读 [README](README.md)。

## 应用内更新怎么工作

App 会依次尝试读取本仓库 `main` 分支的 `update.json`：

1. `https://raw.githubusercontent.com/Haa1024/CHU-Timetable/main/update.json`
2. `https://cdn.jsdelivr.net/gh/Haa1024/CHU-Timetable@main/update.json`

字段含义见 App 内 `update/UpdateManifest.kt`。两条要点：

- `apkUrl` 必须是**免登录、可直接 GET** 的地址（即 Release 资产的直链）。
  否则 App 判定这份清单不可用，转而尝试下一个源。
- `versionCode` 必须**大于**本机版本才会提示更新；`versionName` 只影响展示。
  所以发版后忘了改 `versionCode`，用户端会一直显示"已是最新"。

## 发布一个新版本

1. 改 `app/build.gradle.kts` 里的 `versionCode` / `versionName`。
2. 构建 release APK。
3. 到 Releases 新建一个 tag，把 APK 作为资产上传。**资产文件名必须是**
   `CHU-Timetable-release.apk` —— `apkUrl` 用的是 `latest/download/<资产名>` 这种形式，
   改了文件名就要同步改 `update.json`，否则下载会 404。
4. 更新仓库根目录的 `update.json`：`versionCode` / `versionName` / `notes`。

## 签名

`keystore/` 与 `local.properties` 都不入库，需要**另行备份**。

Android 的覆盖安装强绑定签名：换了钥匙，老用户就装不上新版本，只能卸载重装
（本地课表会一并丢失）。

## 不要用 `origin` 推送

本机 `origin` 指向同作者的**另一个**仓库（SEU 课表）。按名字推会把两边弄混，
发版与推送都用显式 URL。
