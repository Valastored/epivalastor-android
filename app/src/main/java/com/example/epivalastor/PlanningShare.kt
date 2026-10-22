package com.example.epivalastor

import android.content.ClipData
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Image de planning à générer : un titre et les jours à dessiner. */
data class PlanningImage(val title: String, val days: List<Pair<LocalDate, List<CourseEntity>>>)

/** Génère une image PNG d'un jour ou d'une semaine, à partager ou à enregistrer dans la galerie. */
object PlanningShare {
    private const val WIDTH = 1080
    private const val PAD = 48f
    private const val ROW_H = 132f
    private const val ROW_GAP = 14f
    private const val DAY_HEADER_H = 84f
    private const val DAY_GAP = 28f

    private val HOUR_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE dd MMMM", Locale.FRANCE)
    private val SHORT_FMT = DateTimeFormatter.ofPattern("dd.MM")

    fun dayImage(date: LocalDate, courses: List<CourseEntity>): PlanningImage =
        PlanningImage(date.format(DAY_FMT).replaceFirstChar { it.uppercase() }, listOf(date to courses))

    /** null s'il n'y a aucun cours cette semaine. */
    fun weekImage(monday: LocalDate, allCourses: List<CourseEntity>): PlanningImage? {
        val sunday = monday.plusDays(6)
        val days = allCourses
            .filter { val d = courseDate(it); !d.isBefore(monday) && !d.isAfter(sunday) }
            .groupBy { courseDate(it) }
            .toSortedMap()
            .map { it.key to it.value }
        if (days.isEmpty()) return null
        return PlanningImage("Semaine du ${monday.format(SHORT_FMT)} au ${sunday.format(SHORT_FMT)}", days)
    }

    /** Enregistre l'image dans la galerie (dossier Images/EpiValastor). */
    fun saveToGallery(context: Context, image: PlanningImage, dark: Boolean) {
        try {
            val bitmap = render(image.title, image.days, dark)
            val safeTitle = image.title.replace(Regex("[^\\p{L}\\d]+"), "_").trim('_')
            val name = "EpiValastor_${safeTitle}_${System.currentTimeMillis()}.png"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ : MediaStore, aucune autorisation nécessaire
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EpiValastor")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                // Android 8-9 : fichier public + indexation dans la galerie (autorisation demandée avant)
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "EpiValastor")
                dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            }
            bitmap.recycle()
            Toast.makeText(context, "Image enregistrée dans la galerie (Images › EpiValastor)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Impossible d'enregistrer l'image", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, image: PlanningImage, dark: Boolean) {
        val title = image.title
        val days = image.days
        try {
            val bitmap = render(title, days, dark)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Nom unique à chaque partage : sinon l'app qui reçoit (et l'aperçu du menu de partage)
            // peut réafficher l'ancienne image en cache, par exemple celle d'un seul jour.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "planning_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "$title — EpiValastor")
                clipData = ClipData.newRawUri("planning", uri) // aperçu dans le menu de partage
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Partager le planning").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Impossible de créer l'image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun ellipsize(text: String, p: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, p, width.coerceAtLeast(10f), TextUtils.TruncateAt.END).toString()

    private fun hhmm(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(PARIS_ZONE).format(HOUR_FMT)

    private fun render(title: String, days: List<Pair<LocalDate, List<CourseEntity>>>, dark: Boolean): Bitmap {
        val bg = if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFFFFFFF.toInt() else 0xFF1C1B1F.toInt()
        val sub = if (dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
        val accent = if (dark) 0xFFD0BCFF.toInt() else 0xFF9C27B0.toInt()
        val examRed = 0xFFD32F2F.toInt()
        val holidayColor = 0xFF00A6A6.toInt()

        val titlePaint = paint(58f, accent, bold = true)
        val dayPaint = paint(40f, text, bold = true)
        val dayHoursPaint = paint(32f, sub)
        val timePaint = paint(34f, text, bold = true)
        val timeEndPaint = paint(28f, sub)
        val coursePaint = paint(38f, text, bold = true)
        val infoPaint = paint(29f, sub)
        val chipPaint = paint(26f, 0xFFFFFFFF.toInt(), bold = true)
        val footerPaint = paint(26f, sub)

        // Hauteur totale
        var height = PAD + 80f + 30f
        days.forEach { (_, list) -> height += DAY_HEADER_H + list.size * (ROW_H + ROW_GAP) + DAY_GAP }
        height += 70f

        val bitmap = Bitmap.createBitmap(WIDTH, height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bg)

        var y = PAD + 60f
        canvas.drawText(ellipsize(title, titlePaint, WIDTH - 2 * PAD), PAD, y, titlePaint)
        y += 50f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }

        for ((date, list) in days) {
            val sorted = list.sortedWith(compareBy<CourseEntity>({ !isHoliday(it) }, { it.startTimeEpoch }))
            val real = sorted.filterNot { isHoliday(it) }

            // En-tête du jour : nom + horaires début – fin
            y += DAY_HEADER_H - 24f
            val dayName = date.format(DAY_FMT).replaceFirstChar { it.uppercase() }
            canvas.drawText(dayName, PAD, y, dayPaint)
            if (real.isNotEmpty()) {
                val hours = "  ·  ${hhmm(real.minOf { it.startTimeEpoch })} – ${hhmm(real.maxOf { it.endTimeEpoch })}"
                canvas.drawText(hours, PAD + dayPaint.measureText(dayName), y, dayHoursPaint)
            }
            y += 24f

            for (c in sorted) {
                val holiday = isHoliday(c)
                val exam = isExam(c)
                val color = when { holiday -> holidayColor; exam -> examRed; else -> c.colorArgb }
                val rect = RectF(PAD, y, WIDTH - PAD, y + ROW_H)

                fill.color = ColorUtils.setAlphaComponent(color, if (dark) 60 else 38)
                canvas.drawRoundRect(rect, 28f, 28f, fill)
                stroke.color = ColorUtils.setAlphaComponent(color, if (holiday || exam) 255 else 140)
                canvas.drawRoundRect(rect, 28f, 28f, stroke)

                // Colonne horaires
                val timeRect = RectF(rect.left, rect.top, rect.left + 160f, rect.bottom)
                fill.color = ColorUtils.setAlphaComponent(color, if (dark) 50 else 30)
                canvas.drawRoundRect(timeRect, 28f, 28f, fill)
                val allDay = c.endTimeEpoch - c.startTimeEpoch >= 20 * 3600
                if (holiday && allDay) {
                    canvas.drawText("Journée", timeRect.centerX() - timePaint.measureText("Journée") / 2, rect.top + 76f, timePaint)
                } else {
                    val s = hhmm(c.startTimeEpoch); val e = hhmm(c.endTimeEpoch)
                    canvas.drawText(s, timeRect.centerX() - timePaint.measureText(s) / 2, rect.top + 58f, timePaint)
                    canvas.drawText(e, timeRect.centerX() - timeEndPaint.measureText(e) / 2, rect.top + 98f, timeEndPaint)
                }

                // Pastille examen / férié / vacances
                var rightLimit = rect.right - 24f
                val chip = when { holiday -> holidayLabel(c).substringAfter(' '); exam -> "Examen"; else -> null }
                if (chip != null) {
                    val w = chipPaint.measureText(chip) + 32f
                    val chipRect = RectF(rect.right - 24f - w, rect.top + 22f, rect.right - 24f, rect.top + 64f)
                    fill.color = color
                    canvas.drawRoundRect(chipRect, 21f, 21f, fill)
                    canvas.drawText(chip, chipRect.left + 16f, chipRect.top + 31f, chipPaint)
                    rightLimit = chipRect.left - 16f
                }

                val textX = timeRect.right + 24f
                canvas.drawText(ellipsize(c.title, coursePaint, rightLimit - textX), textX, rect.top + 56f, coursePaint)
                val info = listOf(
                    if (c.location.isBlank() || c.location == "Non spécifié") "" else "📍 ${c.location}",
                    courseTeacher(c).let { if (it.isBlank()) "" else "👤 $it" }
                ).filter { it.isNotEmpty() }.joinToString("   ")
                if (info.isNotEmpty()) {
                    canvas.drawText(ellipsize(info, infoPaint, rect.right - 24f - textX), textX, rect.top + 100f, infoPaint)
                }
                y += ROW_H + ROW_GAP
            }
            y += DAY_GAP
        }

        val footer = "EpiValastor"
        canvas.drawText(footer, (WIDTH - footerPaint.measureText(footer)) / 2, height - 30f, footerPaint)
        return bitmap
    }
}
