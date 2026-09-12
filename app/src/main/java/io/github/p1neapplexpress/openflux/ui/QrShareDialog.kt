package io.github.p1neapplexpress.openflux.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.util.QrGenerator
import io.github.p1neapplexpress.openflux.util.TunnelLinkParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

object QrShareDialog {

    fun show(context: Context, tunnel: Tunnel) {
        val json = runCatching { Json.encodeToString(tunnel) }.getOrNull() ?: return
        val configLink = TunnelLinkParser.toLink(tunnel)
        val qrBitmap = QrGenerator.generateQrBitmap(configLink, 600) ?: run {
            Toast.makeText(context, "Не удалось сгенерировать QR-код", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_qr_share, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.qr_title).text = tunnel.name
        view.findViewById<ImageView>(R.id.qr_image).setImageBitmap(qrBitmap)

        view.findViewById<View>(R.id.btn_close_dialog).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        val copyBtn = view.findViewById<View>(R.id.btn_copy_json)
        copyBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Fluxon Config Link", configLink))
            Toast.makeText(context, R.string.config_link_copied, Toast.LENGTH_SHORT).show()
        }
        copyBtn.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Fluxon JSON", json))
            Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            true
        }

        view.findViewById<View>(R.id.btn_share_qr).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            try {
                val imagesDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                val imageFile = File(imagesDir, "qr_${tunnel.id}.png")
                FileOutputStream(imageFile).use { fos ->
                    qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    imageFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, tunnel.name)
                    putExtra(Intent.EXTRA_TEXT, "${tunnel.name}\n$configLink")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, context.getString(R.string.action_share_qr)).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось поделиться: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
}
