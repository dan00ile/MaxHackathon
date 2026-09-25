package mkd

import com.lowagie.text.Font
import com.lowagie.text.pdf.BaseFont

object Pdf {
    fun font(name: String, size: Float): Font {
        val bytes = Pdf::class.java.getResourceAsStream("/fonts/$name")!!.readBytes()
        return Font(BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null), size)
    }
}
