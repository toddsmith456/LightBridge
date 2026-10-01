plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(libs.junit) }
dependencies { testImplementation("com.google.zxing:core:3.5.3") }
