# Mono TV launcher — R8 rules.
# The app uses no reflection, no serialization libraries and no native code of its own; the default
# "proguard-android-optimize" rules plus the consumer rules shipped by AndroidX are sufficient.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
