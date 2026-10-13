package me.magnum.melonds.common.camera

typealias CameraType = Int

interface DSiCameraSource {

    companion object {
        const val BACK_CAMERA: CameraType = 0
        const val FRONT_CAMERA: CameraType = 1
    }

    fun isAvailable(): Boolean
    fun startCamera(camera: CameraType)
    fun stopCamera(camera: CameraType)
    fun captureFrame(camera: CameraType, buffer: ByteArray, width: Int, height: Int, isYuv: Boolean)
    fun dispose()
}
