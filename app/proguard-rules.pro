# JNI-мост hev-socks5-tunnel: имя класса и методов зашито в .so, R8 не должен их трогать
-keep class com.v2ray.ang.service.TProxyService { *; }
-keepclassmembers class com.v2ray.ang.service.TProxyService$Companion { *; }

# Ядро Xray (gomobile): классы ищутся из нативного кода по имени
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**
