# LiteRT (TensorFlow Lite) uses JNI; keep its classes.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**
