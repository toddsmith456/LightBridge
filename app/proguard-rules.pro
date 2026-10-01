# Youniversal demo app — R8/ProGuard rules for the release build.
#
# Compose and Material 3 ship their own consumer rules, so this file stays deliberately small.
# Anything added here should be a workaround for a specific shrinking problem, not a guess.

# Keep line numbers so crash reports from a release build stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# YouniversalThemeState persists these enums by `name` and reads them back with
# `enumValues<T>().firstOrNull { it.name == stored }`, so their entries and the synthetic
# values() array have to survive shrinking.
-keepclassmembers enum dev.youniversal.theme.YouniversalBackgroundStyle { *; }
-keepclassmembers enum dev.youniversal.theme.YouniversalContrast { *; }

# Kotlin metadata keeps release stack traces and tooling output legible.
-keep class kotlin.Metadata { *; }
