package de.bikemaintainer.app.domain.scan

import java.io.File

/** Test resources of the module (unit tests run with the module directory as working directory). */
internal fun testResource(path: String): File = File("src/test/resources/$path").also {
    check(it.exists()) { "Missing test resource $path" }
}

/**
 * Reads a JPEG/PNG into a [GrayImage] with javax.imageio. Via reflection: the unit tests compile against android.jar,
 * which lacks javax.imageio and java.awt, but they run on a desktop JVM that has both.
 */
internal fun loadGray(file: File): GrayImage {
    val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)!!
    val type = image.javaClass
    val width = type.getMethod("getWidth").invoke(image) as Int
    val height = type.getMethod("getHeight").invoke(image) as Int
    val argb = IntArray(width * height)
    type.getMethod(
        "getRGB",
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    ).invoke(image, 0, 0, width, height, argb, 0, width)
    return GrayImage.fromArgb(width, height, argb)
}

internal fun classifier(): DigitClassifier = File("src/main/assets/digits.bin").inputStream().use(DigitClassifier::load)
