package com.resonanz.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult

class QrScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_TYPE = "qr_type"
        const val EXTRA_QR_DATA = "qr_data"
        const val QR_TYPE_SESSION = "session"
        const val QR_TYPE_PLAYLIST = "playlist"
    }

    private lateinit var barcodeView: DecoratedBarcodeView
    private var hasScanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        barcodeView = findViewById(R.id.barcodeView)
        barcodeView.setStatusText("QR-Code scannen")
        
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (hasScanned) return
                result?.text?.let { text ->
                    when {
                        // Session verification QR
                        text.startsWith("resonanz:") -> {
                            hasScanned = true
                            val token = text.removePrefix("resonanz:")
                            verifyToken(token)
                        }
                        // Playlist share URL
                        text.contains("/share/playlist/") -> {
                            hasScanned = true
                            returnPlaylistUrl(text)
                        }
                    }
                }
            }
        })
    }
    
    private fun returnPlaylistUrl(url: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_QR_TYPE, QR_TYPE_PLAYLIST)
            putExtra(EXTRA_QR_DATA, url)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun verifyToken(token: String) {
        Thread {
            try {
                val url = java.net.URL("http://127.0.0.1:8080/verify/$token")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                
                val responseCode = conn.responseCode
                
                runOnUiThread {
                    if (responseCode == 200) {
                        Toast.makeText(this, "Verbunden!", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        Toast.makeText(this, "Fehler - nochmal versuchen", Toast.LENGTH_SHORT).show()
                        hasScanned = false
                    }
                }
            } catch (e: Exception) {
                Log.e("QrScanner", "Verify error", e)
                runOnUiThread {
                    Toast.makeText(this, "Verbindungsfehler", Toast.LENGTH_SHORT).show()
                    hasScanned = false
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }
}
