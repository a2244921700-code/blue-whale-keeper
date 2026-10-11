# 保留 Room 生成的实现
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.moneydafeiyu.ledger.**$$serializer { *; }
-keepclassmembers class com.moneydafeiyu.ledger.** { *** Companion; }
-keepclasseswithmembers class com.moneydafeiyu.ledger.** { kotlinx.serialization.KSerializer serializer(...); }
