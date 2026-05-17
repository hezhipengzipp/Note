# MQTT AndroidManifest 配置

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 网络权限 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <application>

        <!-- Paho MQTT 服务（必须注册） -->
        <service android:name="org.eclipse.paho.android.service.MqttService" />

        <!-- 前台服务（可选，用于后台保活） -->
        <service
            android:name=".mqtt.MqttForegroundService"
            android:foregroundServiceType="connectedDevice"
            android:exported="false" />

    </application>
</manifest>
```

## build.gradle 依赖

```gradle
dependencies {
    // Eclipse Paho MQTT
    implementation 'org.eclipse.paho:org.eclipse.paho.android.service:1.1.1'
    implementation 'org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5'
}
```

## 注意事项

1. Android 9.0+ 限制后台服务，建议用前台服务保活
2. Android 12.0+ 前台服务需要声明 `foregroundServiceType`
3. `MqttService` 是 Paho 库必须注册的服务，不注册会崩溃
4. `WAKE_LOCK` 权限用于保持网络连接在休眠时不被断开
