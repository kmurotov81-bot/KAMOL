-verbose
-dontwarn android.support.**
-dontwarn com.badlogic.gdx.backends.android.AndroidFragmentApplication
-dontwarn com.badlogic.gdx.utils.GdxBuild
-dontwarn com.badlogic.gdx.physics.box2d.utils.Box2DBuild
-dontwarn com.badlogic.gdx.jnigen.BuildTarget*
-keepclassmembers class com.badlogic.gdx.backends.android.AndroidInput* { <init>(...); }
-keepclassmembers class com.badlogic.gdx.physics.box2d.World {
    boolean contactFilter(long, long);
    void beginContact(long);
    void endContact(long);
    void preSolve(long, long);
    void postSolve(long, long);
    boolean reportFixture(long);
    float reportRayFixture(long, float, float, float, float, float);
}
-keep class com.badlogic.gdx.physics.box2d.** { *; }

# libGDX backend va yordamchi sinflar (reflection/JNI orqali chaqiriladi)
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.badlogic.gdx.backends.android.** { *; }
-keep class com.badlogic.gdx.utils.** { *; }
-keep class com.badlogic.gdx.graphics.g2d.** { *; }
-keep class com.soccer.game.** { *; }
