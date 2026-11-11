# Room, Compose, OkHttp and kotlinx.serialization ship their own consumer rules.
# The app builds JSON with JsonElement only, so no serializer classes need keeping.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
