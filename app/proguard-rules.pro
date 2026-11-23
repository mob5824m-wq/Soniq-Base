# Framework-only app: there is nothing to keep from third-party libraries.
# Keep line numbers for readable crash traces from the InnerTube parser.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# org.json is part of the Android framework; the API layer is called reflectively
# nowhere, so no extra keep rules are required.
