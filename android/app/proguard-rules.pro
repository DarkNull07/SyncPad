# iroh is called through JNA, which finds classes and methods by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class computer.iroh.** { *; }
-dontwarn java.awt.**
