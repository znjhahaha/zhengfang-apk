# Library consumer rules protect their own JNI and reflection entry points.
# Preserve generic information used by QuickJS typed bindings and Kotlin reflection.
-keepattributes Signature,InnerClasses,EnclosingMethod

# ONNX Runtime's JNI looks up tensor metadata, exceptions and constructors by
# their Java names. Its Android AAR does not supply consumer keep rules.
-keep class ai.onnxruntime.** { *; }
