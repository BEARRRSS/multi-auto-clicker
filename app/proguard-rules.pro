# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK. For more details, see:
#   http://developer.android.com/tools/proguard

# Keep Accessibility Service
-keep class com.fayaa.autoclicker.service.AutoClickService { *; }
-keep class com.fayaa.autoclicker.service.OverlayService { *; }

# Keep data models
-keep class com.fayaa.autoclicker.model.** { *; }
