// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.utils

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import coil.transform.RoundedCornersTransformation
import java.nio.IntBuffer
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.model.Game

class GameIconFetcher(private val game: Game, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = DrawableResult(
        drawable = getGameIcon(game.icon)!!.toDrawable(options.context.resources),
        isSampled = false,
        dataSource = DataSource.DISK
    )

    private fun getGameIcon(vector: IntArray?): Bitmap? {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.RGB_565)
        bitmap.copyPixelsFromBuffer(IntBuffer.wrap(vector))
        return bitmap
    }

    class Factory : Fetcher.Factory<Game> {
        override fun create(data: Game, options: Options, imageLoader: ImageLoader): Fetcher =
            GameIconFetcher(data, options)
    }
}

class GameIconKeyer : Keyer<Game> {
    override fun key(data: Game, options: Options): String = data.path
}

object GameIconUtils {
    private val imageLoader by lazy {
        ImageLoader.Builder(CitraApplication.appContext)
            .components {
                add(GameIconKeyer())
                add(GameIconFetcher.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(CitraApplication.appContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .build()
    }

    fun loadGameIcon(game: Game, imageView: ImageView) {
        val request = ImageRequest.Builder(imageView.context)
            .data(game)
            .target(imageView)
            .error(R.drawable.no_icon)
            .transformations(
                RoundedCornersTransformation(
                    imageView.resources.getDimensionPixelSize(R.dimen.spacing_med).toFloat()
                )
            )
            .build()
        imageLoader.enqueue(request)
    }
}
