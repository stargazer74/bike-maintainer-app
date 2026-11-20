package de.bikemaintainer.app.domain.odometer

import de.bikemaintainer.app.domain.scan.GrayImage

/** Width : height of the photos the models were trained on (640 × 480). */
const val ODO_PHOTO_RATIO = 4.0 / 3.0

/**
 * The 4:3 landscape area in the middle of [photo] – what the frame in the camera shows. The models only know landscape
 * photos (a portrait photo would be stretched), and the rest of the picture is not needed (app#40).
 */
fun odoPhotoArea(photo: GrayImage): GrayImage {
    val (w, h) = if (photo.width >= photo.height * ODO_PHOTO_RATIO) {
        Math.round(photo.height * ODO_PHOTO_RATIO).toInt() to photo.height
    } else {
        photo.width to Math.round(photo.width / ODO_PHOTO_RATIO).toInt()
    }
    if (w == photo.width && h == photo.height) return photo
    val left = (photo.width - w) / 2
    val top = (photo.height - h) / 2
    val out = GrayImage(w, h)
    for (y in 0 until h) System.arraycopy(photo.pixels, (top + y) * photo.width + left, out.pixels, y * w, w)
    return out
}

/** The straightened line as a picture for the review (only its own columns, without the padding). */
fun OdoReader.Crop.image(): GrayImage {
    val out = GrayImage(width, OdoReader.H)
    for (y in 0 until OdoReader.H) for (x in 0 until width) out[x, y] = pixels[y * OdoReader.MAX_W + x].toInt()
    return out
}
