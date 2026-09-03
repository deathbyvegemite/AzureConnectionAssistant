# ML Kit and Room ship their own consumer rules; these cover the model classes we
# hold reflectively-ish through Room's generated code.
-keep class com.deathbyvegemite.platewatch.data.db.** { *; }
-keep class com.deathbyvegemite.platewatch.core.** { *; }
