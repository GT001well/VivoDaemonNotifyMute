# vivo-daemon-notify-mute

LSPosed 模块，屏蔽 vivo `com.vivo.daemonService` 的「系统数据空间损坏」通知（id 10011 / 10012）。

## 构建

```bash
./build.sh          # 产物在 out/
```

依赖 Android SDK（build-tools 36、platform android-36）与 JDK 17+。

## 安装

```bash
adb install -r out/vivo-daemon-notify-mute-*.apk
```

在 LSPosed 中启用，作用域勾 `com.vivo.daemonService`，重启进程生效。

## 原理

hook `NotificationManager.notify` / `notifyAsPackage`，命中 id 即拦截并 cancel 旧通知。

## 撤销

LSPosed 里关闭模块，或卸载 APK。

## License

MIT
