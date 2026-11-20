package org.openwild.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.openwild.app.R
import org.openwild.app.ui.BitcoinDonation
import org.openwild.app.ui.BitcoinQr
import org.openwild.app.ui.SectionCard
import org.openwild.app.ui.theme.Leaf

/** Quiet Bitcoin address on the About screen. No wallet intent and no network. */
@Composable
fun BitcoinDonateSection() {
    val context = LocalContext.current
    val address = BitcoinDonation.displayedAddress()
    var copied by remember { mutableStateOf(false) }
    val qr = remember(address) { BitcoinQr.bitmap(BitcoinDonation.qrPayload()) }
    SectionCard(stringResource(R.string.donate_bitcoin), Icons.Filled.CurrencyBitcoin, Leaf) {
        Text(stringResource(R.string.donate_bitcoin_body))
        SelectionContainer(Modifier.fillMaxWidth()) {
            Text(
                address,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Image(
            bitmap = qr.asImageBitmap(),
            contentDescription = stringResource(R.string.donate_bitcoin_qr),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(168.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(10.dp),
        )
        OutlinedButton(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.donate_bitcoin), address))
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.donate_bitcoin_copy))
        }
        if (copied) {
            Text(
                stringResource(R.string.donate_bitcoin_copied),
                color = Leaf,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
