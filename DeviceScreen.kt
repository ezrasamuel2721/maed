package et.maed.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MAED_GREEN = Color(0xFF1B5E3B)

@Composable
fun DeviceScreen() {
    var target by remember { mutableStateOf("Salmonella") }
    var cartridge by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🔬 MAED Device", fontSize = 26.sp)
        Text(
            "የተወሰነ የምግብ በሽታ አምጪ ባክቴሪያን በbiosensor reader ለመለየት የተዘጋጀ የመሳሪያ በይነገጽ።",
            fontSize = 14.sp
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Device status", fontSize = 19.sp)
                Text(if (connected) "🟢 Reader connected" else "⚪ Reader not connected")
                Text(if (cartridge) "🟢 Cartridge detected" else "⚪ Cartridge not inserted")
                Text(if (running) "🟡 Test running…" else "Ready")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { connected = !connected }) {
                        Text(if (connected) "Disconnect" else "Connect reader")
                    }
                    OutlinedButton(onClick = { cartridge = !cartridge }) {
                        Text(if (cartridge) "Remove cartridge" else "Insert cartridge")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Target", fontSize = 19.sp)
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target organism") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("Prototype V1 target: Salmonella", fontSize = 12.sp)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("3. Detection workflow", fontSize = 19.sp)
                Text("Food sample → disposable cartridge → specific recognition → sensor signal → validated reader → result")
                Text("The light/sensor reader measures a generated biosensor signal; it does not visually see bacteria.", fontSize = 12.sp)
            }
        }

        Button(
            enabled = connected && cartridge && target.isNotBlank() && !running,
            onClick = { running = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MAED_GREEN)
        ) {
            Text(if (running) "Test running…" else "▶ Start validated test", fontSize = 17.sp)
        }

        if (running) {
            Card(
                Modifier.fillMaxWidth().background(Color(0xFFFFF3CD))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🟡 WAITING FOR READER SIGNAL", fontSize = 19.sp)
                    Text(
                        "ይህ የapp ክፍል እውነተኛ biosensor/reader እስካልተገናኘ ድረስ DETECTED ወይም NOT DETECTED የሚል የሐሰት ውጤት አይፈጥርም።"
                    )
                    OutlinedButton(onClick = { running = false }) { Text("Cancel") }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("4. Result gate", fontSize = 19.sp)
                    Text("🔴 DETECTED — only from a validated target-specific signal")
                    Text("🟢 NOT DETECTED — only within the validated detection limit")
                    Text("🟡 INVALID — quality-control or reader failure")
                }
            }
        }

        Text(
            "⚠️ MAED አሁን ያለው አፕ የhardware ምርት ሳይኖር የባክቴሪያ መኖር እንዳረጋገጠ አያሳይም። የDETECTED/NOT DETECTED ውጤት ከvalidated biosensor reader እና reference-lab validation በኋላ ብቻ ይከፈታል።",
            fontSize = 12.sp
        )
    }
}
