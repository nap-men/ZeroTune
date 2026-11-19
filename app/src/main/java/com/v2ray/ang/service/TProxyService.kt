package com.v2ray.ang.service

/**
 * JNI-мост к libhev-socks5-tunnel.so (нативный tun2socks на C).
 *
 * Имя пакета и класса ЗАШИТО в готовую библиотеку (она собрана с PKGNAME=com/v2ray/ang/service),
 * поэтому переименовывать этот файл, пакет и методы нельзя. Библиотеку берём из APK v2rayNG:
 * lib/arm64-v8a/libhev-socks5-tunnel.so -> app/src/main/jniLibs/arm64-v8a/
 */
class TProxyService private constructor() {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        fun start(configPath: String, fd: Int): Boolean = TProxyStartService(configPath, fd)
        fun stop(): Boolean = TProxyStopService()
        fun isRunning(): Boolean = TProxyIsRunning()
    }
}
