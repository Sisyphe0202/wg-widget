package com.example.wgwidget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class DinoRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        DinoFactory(applicationContext)
}

class DinoFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

    private val frames = mutableListOf<Bitmap>()

    override fun onCreate() { refreshFrames() }
    override fun onDataSetChanged() { refreshFrames() }
    override fun onDestroy() { frames.clear() }

    override fun getCount(): Int = frames.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.dino_frame)
        frames.getOrNull(position)?.let {
            views.setImageViewBitmap(R.id.dino_image, it)
        }
        return views
    }

    private fun refreshFrames() {
        frames.clear()
        val prefs = ctx.getSharedPreferences("wg_state", Context.MODE_PRIVATE)
        when (prefs.getString("dino_state", "stand")) {
            "running" -> {
                frames.add(render(Sprites.DINO_RUN1))
                frames.add(render(Sprites.DINO_RUN2))
            }
            "dead" -> frames.add(render(Sprites.DINO_DEAD))
            else -> frames.add(render(Sprites.DINO_STAND))
        }
    }

    private fun render(sprite: Array<String>): Bitmap {
        val px = 4
        val gridW = 70
        val gridH = 23
        val bmp = Bitmap.createBitmap(gridW * px, gridH * px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val green = Paint().apply {
            color = Color.parseColor("#3FB950")
            isAntiAlias = false
        }
        val dinoOy = gridH - sprite.size - 1
        drawSprite(canvas, sprite, 0, dinoOy, px, green)

        val cactus = Sprites.CACTUS_BIG
        val cactusOx = gridW - cactus[0].length - 4
        val cactusOy = gridH - cactus.size - 1
        drawSprite(canvas, cactus, cactusOx, cactusOy, px, green)

        val groundY = gridH - 1
        canvas.drawRect(0f, (groundY * px).toFloat(),
            (gridW * px).toFloat(), ((groundY + 1) * px).toFloat(), green)

        return bmp
    }

    private fun drawSprite(c: Canvas, grid: Array<String>, ox: Int, oy: Int, px: Int, paint: Paint) {
        for (y in grid.indices) {
            val row = grid[y]
            for (x in row.indices) {
                if (row[x] == 'X') {
                    val left = ((ox + x) * px).toFloat()
                    val top = ((oy + y) * px).toFloat()
                    c.drawRect(left, top, left + px, top + px, paint)
                }
            }
        }
    }
}
