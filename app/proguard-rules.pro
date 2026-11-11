# The app has no reflection of its own and no serialisation, so R8 needs no
# help keeping anything. The one reflective call is BluetoothGatt.refresh(),
# which is a framework method looked up by name at runtime and is not part of
# this APK, so shrinking cannot reach it.
#
# Line numbers are kept so a stack trace from a release build still points at
# real source lines.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
