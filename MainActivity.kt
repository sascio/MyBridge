package com.mybridge.phase1

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var server: BridgeServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = BridgeServer(8080) {}
        setContent { MyBridgeApp() }
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    @Composable
    private fun MyBridgeApp() {
        var state by remember { mutableStateOf(server.state()) }
        var refresh by remember { mutableIntStateOf(0) }
        val clipboard = LocalClipboardManager.current

        LaunchedEffect(refresh) {
            while (true) {
                state = server.state()
                delay(1000)
            }
        }

        val url = state.ip?.let { "http://$it:${state.port}" }

        MaterialTheme(
            colorScheme = darkColorScheme(
                background = Color(0xFF07070C),
                surface = Color(0xFF12121A),
                primary = Color(0xFF9B6CFF)
            )
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF171027), Color(0xFF07070C), Color(0xFF07070C))
                    )
                )
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)
                ) {
                    Spacer(Modifier.height(22.dp))
                    Text("MYBRIDGE", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Text("LOCAL STREAMING BRIDGE", color = Color.LightGray, fontSize = 12.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(28.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF15131E)),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(22.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("SERVER", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (state.running) "● ONLINE" else "● OFFLINE",
                                    color = if (state.running) Color(0xFF67E8A5) else Color(0xFFFF6B7A),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(
                                if (state.running) (url ?: "LAN IP unavailable") else "Start the server to expose the addon",
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("${state.requestCount} requests", color = Color.Gray)
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    if (state.running) server.stop() else server.start()
                                    refresh++
                                },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(if (state.running) "STOP SERVER" else "START SERVER", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    if (state.running && url != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF11141B)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text("ADDON URL", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text("$url/manifest.json", color = Color(0xFFCBB9FF))
                                Spacer(Modifier.height(14.dp))
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString("$url/manifest.json"))
                                        Toast.makeText(this@MainActivity, "Addon URL copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("COPY ADDON URL") }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Text("EXTENSIONS", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("No extensions installed. Add them explicitly later.", color = Color.Gray)
                    Spacer(Modifier.height(18.dp))
                    Text("INTEGRATIONS", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("TMDB and MDBList are optional and disabled by default.", color = Color.Gray)
                    Spacer(Modifier.height(30.dp))
                    Text("Phase 1A • Real HTTP server foundation", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}
