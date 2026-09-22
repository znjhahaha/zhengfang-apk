# Library consumer rules protect their own JNI and reflection entry points.
# Preserve generic information used by QuickJS typed bindings and Kotlin reflection.
-keepattributes Signature,InnerClasses,EnclosingMethod
