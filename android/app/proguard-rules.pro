# kotlinx.serialization keeps generated serializers via its own consumer rules.
# Ktor/OkHttp ship consumer rules too; add app-specific keeps here if R8 complains.
-dontwarn org.slf4j.**
