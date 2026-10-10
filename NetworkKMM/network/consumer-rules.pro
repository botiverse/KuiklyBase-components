# JNI looks up these callback methods by their stable JVM names.
-keep class com.tencent.kmm.network.internal.platform.AndroidCurlJniCallback {
    void onResponseStart(long, java.lang.String);
    void onChunk(byte[]);
    byte[] readUploadChunk(int);
    boolean isCancelled();
    void onComplete(int, long, java.lang.String, java.lang.String, java.lang.String, byte[], double, double, double, double, double, double, double, double);
}

# Raft task #154: Socket.IO callbacks, looked up by name from the JNI layer.
-keep class com.tencent.kmm.network.internal.platform.AndroidCurlSocketIoCallback {
    void onState(int, int, byte[]);
    void onEvent(byte[], byte[]);
}

-keepclasseswithmembernames class com.tencent.kmm.network.internal.platform.AndroidCurlJniBridge {
    native <methods>;
}
