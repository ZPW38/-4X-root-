# 华为儿童手表 4X 临时 root 方案

给 **华为儿童手表 4X（NIK-AL00 / Nick-AL00，Android 10，Linux 4.14.116，Kirin 990）** 提供**临时 root**。

- **临时**：重启后失效。不解锁 bootloader、不改分区、不刷机。
- **一键**：装上 APK，打开点一下，自动完成连接 → 推送 payload → 提权。
- 界面为 **Material You**（Material 3），跟随系统壁纸取色。

---

## 工作原理

1. App 通过自实现的 **无线调试 ADB 客户端**（SPAKE2 配对 / 明文 TCP）连上设备，取得 `shell` 权限。
2. 把 arm64 payload（`libghostlock.so`）从 `nativeLibraryDir` 推到 `/data/local/tmp/openroot/` 并 `chmod 755`。
   - 从 `nativeLibraryDir` 取再推，是为了绕开 `/data` 的 `noexec` 挂载。
3. 推送与当前内核版本精确匹配的 **kernel profile**（`assets/kernel_profiles/<uname -r>.bin`）。
4. 执行 payload，内核侧完成提权并交接 root。

profile 文件名必须精确等于 `uname -r`（本机即 `4.14.116.bin`），否则 App 会在写入设备之前就拒绝运行。

## 目录结构

```
app/src/main/
  assets/kernel_profiles/4.14.116.bin   内核 profile（GLK1 v2 格式）
  jniLibs/arm64-v8a/libghostlock.so     arm64 payload
  java/com/zpw38/watchroot/
    adb/          无线调试 ADB 客户端（配对 + shell）
    crypto/       SPAKE2 / x25519（配对用）
    root/         payload 推送与执行
    ui/           Material You 界面
```

## 构建

本机 **没有 NDK**，payload 用 [zig](https://ziglang.org) 交叉编译：

```bash
python-zig.exe c++ -std=c++23 -target aarch64-linux-musl -O2 -static -pthread \
    -o payload/arm64-v8a/libghostlock.so <sources>
```

然后打 APK：

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 目标设备

| 项 | 值 |
|---|---|
| 型号 | NIK-AL00 / Nick-AL00 |
| Android | 10 |
| 内核 | 4.14.116 |
| SoC | Kirin 990 |
| 核心 | 4 |
| 内存 | 814 MB |
| 固件 | 1.3.0.260C00 |

`ro.build.type=user`、`ro.secure=1`、`ro.debuggable=0`，`/system` 与 `/vendor` 只读，
无 setuid 程序，`/proc/kallsyms` 与 `kptr_restrict` 均不可读 —— **内核利用是唯一路径**。

## 安全边界

本项目**只做临时提权**：

- 不解锁 bootloader，不改分区，不刷机，不改内核；
- 提权在重启后自然消失；
- 只在用户明确点击、且设备已授权本 App 的 ADB 密钥时执行；
- 设备上的写入仅限于 `/data/local/tmp/openroot/`。

## 许可

**GNU GPL v3.0** —— 见 [LICENSE](LICENSE)。

本项目包含来自 [OpenRoot](https://github.com/ZPW38/OpenRoot) 的 ADB 客户端与 SPAKE2 实现，
同样以 GPL-3.0 分发，版权声明保留在对应源文件中。
