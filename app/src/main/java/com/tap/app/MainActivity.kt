package com.tap.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF10100F)
private val Panel = Color(0xFF1B1A17)
private val Gold = Color(0xFFF3B83F)
private val GoldLight = Color(0xFFFFE6A2)
private val Muted = Color(0xFFA8A49A)
private val Line = Color(0xFF35332D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Gold,
                onPrimary = Ink,
                background = Ink,
                surface = Panel,
                onSurface = Color(0xFFF4F0E7),
                secondary = GoldLight
            )) {
                TapApp()
            }
        }
    }
}

private enum class Page(val label: String) {
    HOME("Home"), TASKS("Tasks"), WALLET("Wallet"), LEADERBOARD("Ranks"), PROFILE("Profile")
}

@Composable
private fun TapApp() {
    val context = LocalContext.current
    val api = remember { TapApi(context) }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(TapConfig("TAP", 1, 20, 500)) }
    var profile by remember { mutableStateOf<TapProfile?>(null) }
    var page by remember { mutableStateOf(Page.HOME) }
    var transactions by remember { mutableStateOf<List<LedgerItem>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<TapTask>>(emptyList()) }
    var rankings by remember { mutableStateOf<List<RankItem>>(emptyList()) }
    var leaderboardPeriod by remember { mutableStateOf("daily") }
    var dailyStatus by remember { mutableStateOf(DailyStatus(false, 0, 100)) }
    var claimingDailyReward by remember { mutableStateOf(false) }
    var queuedTaps by remember { mutableIntStateOf(0) }
    var sendingTaps by remember { mutableStateOf(false) }
    var lastReward by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(api.isOnline()) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(api) {
        while (true) {
            online = api.isOnline()
            try {
                config = withContext(Dispatchers.IO) { api.loadConfig() }
                if (api.hasSession()) {
                    profile = withContext(Dispatchers.IO) { api.loadProfile(config.maxEnergy) }
                }
            } catch (error: Exception) {
                message = error.message ?: "Unable to connect to TAP"
                if (error.message?.contains("Authentication required") == true) profile = null
            }
            delay(30_000)
        }
    }

    LaunchedEffect(page, profile?.id) {
        if (profile != null) {
            when (page) {
                Page.WALLET -> runCatching { withContext(Dispatchers.IO) { api.transactions() } }
                    .onSuccess { transactions = it }
                    .onFailure { message = it.message ?: "Could not load transaction history" }
                Page.TASKS -> runCatching { withContext(Dispatchers.IO) { api.tasks() } }
                    .onSuccess { tasks = it }
                    .onFailure { message = it.message ?: "Could not load tasks" }
                Page.HOME -> runCatching { withContext(Dispatchers.IO) { api.dailyStatus() } }
                    .onSuccess { dailyStatus = it }
                    .onFailure { message = it.message ?: "Could not load daily reward" }
                else -> Unit
            }
        }
    }

    LaunchedEffect(page, profile?.id, leaderboardPeriod) {
        if (page == Page.LEADERBOARD && profile != null) {
            runCatching { withContext(Dispatchers.IO) { api.leaderboard(leaderboardPeriod) } }
                .onSuccess { rankings = it }
                .onFailure { message = it.message ?: "Could not load rankings" }
        }
    }

    LaunchedEffect(lastReward) {
        if (lastReward > 0) {
            delay(1_100)
            lastReward = 0
        }
    }

    val signIn: (String, String, String?) -> Unit = { email, password, username ->
        if (!api.isOnline()) {
            online = false
            message = "Connect to the internet to sign in."
        } else {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { api.authenticate(email, password, username) }
                    config = withContext(Dispatchers.IO) { api.loadConfig() }
                    profile = withContext(Dispatchers.IO) { api.loadProfile(config.maxEnergy) }
                    online = true
                    message = ""
                } catch (error: Exception) {
                    message = error.message ?: "Sign in failed"
                } finally {
                    busy = false
                }
            }
        }
    }

    val submitTap: () -> Unit = {
        if (!api.isOnline()) {
            online = false
            message = "Offline. Taps are paused until you reconnect."
        } else if (profile != null && queuedTaps < 100) {
            queuedTaps += 1
            if (!sendingTaps) {
                sendingTaps = true
                scope.launch {
                    try {
                        while (queuedTaps > 0) {
                            val batch = minOf(queuedTaps, config.maxTapsPerRequest)
                            queuedTaps -= batch
                            val result = withContext(Dispatchers.IO) { api.tap(batch) }
                            profile = profile?.copy(
                                availableCoins = result.balance,
                                lifetimeEarned = (profile?.lifetimeEarned ?: 0L) + result.coinsAwarded,
                                energy = result.energy
                            )
                            lastReward = result.coinsAwarded.toInt()
                            online = true
                            message = if (result.tapCount < batch) "Energy is low. Your confirmed balance is up to date." else ""
                            if (result.tapCount < batch) queuedTaps = 0
                        }
                    } catch (error: Exception) {
                        queuedTaps = 0
                        message = error.message ?: "Tap could not be synchronized"
                    } finally {
                        sendingTaps = false
                    }
                }
            }
        }
    }

    val submitTask: (TapTask, String) -> Unit = { task, proof ->
        if (!api.isOnline()) {
            online = false
            message = "Connect to submit task proof."
        } else {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { api.submitTask(task.id, proof) }
                    tasks = withContext(Dispatchers.IO) { api.tasks() }
                    message = "Proof submitted for review."
                } catch (error: Exception) {
                    message = error.message ?: "Task submission failed"
                }
            }
        }
    }

    val claimDailyReward: () -> Unit = {
        if (!api.isOnline()) {
            online = false
            message = "Connect to claim today's reward."
        } else if (!claimingDailyReward && !dailyStatus.claimedToday) {
            claimingDailyReward = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { api.claimDailyReward() }
                    profile = withContext(Dispatchers.IO) { api.loadProfile(config.maxEnergy) }
                    dailyStatus = withContext(Dispatchers.IO) { api.dailyStatus() }
                    message = "${result.optLong("reward")} coins added for day ${result.optInt("streak")}."
                } catch (error: Exception) {
                    message = error.message ?: "Daily reward could not be claimed"
                } finally {
                    claimingDailyReward = false
                }
            }
        }
    }

    if (profile == null) {
        AuthScreen(busy, message, signIn)
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Ink,
        contentWindowInsets = WindowInsets.statusBars,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF171613), windowInsets = WindowInsets.navigationBars) {
                Page.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { page = destination },
                        icon = {
                            Icon(
                                when (destination) {
                                    Page.HOME -> Icons.Filled.Home
                                    Page.TASKS -> Icons.Filled.Assignment
                                    Page.WALLET -> Icons.Filled.AccountBalanceWallet
                                    Page.LEADERBOARD -> Icons.Filled.Leaderboard
                                    Page.PROFILE -> Icons.Filled.Person
                                },
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label, fontSize = 10.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!online) OfflineBanner()
            if (message.isNotBlank()) {
                Text(message, color = if (message.startsWith("Offline")) GoldLight else Color(0xFFFF9E83),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), fontSize = 13.sp)
            }
            when (page) {
                Page.HOME -> HomeScreen(profile!!, config.coinsPerTap, dailyStatus, claimingDailyReward, sendingTaps, queuedTaps, lastReward, submitTap, claimDailyReward) {
                    message = "No new notifications"
                }
                Page.TASKS -> TasksScreen(tasks, submitTask)
                Page.WALLET -> WalletScreen(profile!!, transactions)
                Page.LEADERBOARD -> LeaderboardScreen(rankings, leaderboardPeriod) { leaderboardPeriod = it }
                Page.PROFILE -> ProfileScreen(profile!!, busy, onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TAP referral code", profile!!.referralCode))
                    Toast.makeText(context, "Referral code copied", Toast.LENGTH_SHORT).show()
                }, onLogout = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.logout() }
                        } catch (error: Exception) {
                            message = error.message ?: "Signed out locally"
                        }
                        profile = null
                        page = Page.HOME
                        busy = false
                    }
                })
            }
        }
    }
}

@Composable
private fun AuthScreen(busy: Boolean, message: String, onSubmit: (String, String, String?) -> Unit) {
    var createAccount by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF191712), Ink))).imePadding()
            .padding(horizontal = 26.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(76.dp).background(Brush.radialGradient(listOf(GoldLight, Gold, Color(0xFF9F5D17))), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Toll, contentDescription = null, tint = Color(0xFF5B3910), modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("TAP", color = GoldLight, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Text(if (createAccount) "Create your account" else "Welcome back", color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(30.dp))
        if (message.isNotBlank()) Text(message, color = Color(0xFFFF9E83), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (createAccount) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = { onSubmit(email.trim(), password, if (createAccount) username.trim() else null) },
            enabled = !busy && email.isNotBlank() && password.isNotBlank() && (!createAccount || username.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(21.dp), color = Ink, strokeWidth = 2.dp)
            else Text(if (createAccount) "CREATE ACCOUNT" else "SIGN IN", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        TextButton(onClick = { createAccount = !createAccount }) {
            Text(if (createAccount) "I already have an account" else "Create an account", color = GoldLight)
        }
        Text("Your balance is stored securely online.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeScreen(
    profile: TapProfile,
    coinsPerTap: Int,
    dailyStatus: DailyStatus,
    claimingDailyReward: Boolean,
    sending: Boolean,
    queued: Int,
    reward: Int,
    onTap: () -> Unit,
    onClaimDaily: () -> Unit,
    onNotifications: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 23.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WELCOME BACK", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(profile.username, color = Color(0xFFF4F0E7), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.size(42.dp).border(1.dp, Line, CircleShape).background(Panel, CircleShape).clickable(onClick = onNotifications), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.NotificationsNone, contentDescription = "Notifications", tint = GoldLight)
            }
        }
        Text("AVAILABLE COINS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
            Text("${profile.availableCoins}", color = Color(0xFFFFF8E8), fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Toll, contentDescription = null, tint = Gold, modifier = Modifier.size(26.dp))
        }
        Text("${profile.lifetimeEarned} earned in total", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        Box(contentAlignment = Alignment.TopEnd) {
            TapCoin(sending, onTap)
            AnimatedVisibility(visible = reward > 0) {
                Text("+$reward", color = GoldLight, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, end = 2.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (sending) "SYNCING ${queued.coerceAtLeast(1)} TAP${if (queued == 1) "" else "S"}" else "TAP TO EARN",
            color = if (sending) GoldLight else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(24.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(17.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ENERGY", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("${profile.energy} / ${profile.maxEnergy}", color = Color(0xFFF4F0E7), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("REGENERATING", color = GoldLight, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (profile.energy.toFloat() / profile.maxEnergy.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(7.dp), color = Gold, trackColor = Color(0xFF37332B)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(color = Panel, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DAILY CHECK-IN", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    Text("${dailyStatus.streak} day streak", color = Color(0xFFF4F0E7), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Button(onClick = onClaimDaily, enabled = !claimingDailyReward && !dailyStatus.claimedToday,
                    colors = ButtonDefaults.buttonColors(containerColor = if (dailyStatus.claimedToday) Line else Gold, contentColor = if (dailyStatus.claimedToday) Muted else Ink),
                    shape = RoundedCornerShape(10.dp)) {
                    Text(if (dailyStatus.claimedToday) "CLAIMED" else if (claimingDailyReward) "CLAIMING" else "+${dailyStatus.nextReward}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            StatPanel("COINS PER TAP", "+$coinsPerTap", Modifier.weight(1f))
            StatPanel("ACCOUNT", "${profile.username}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun TapCoin(sending: Boolean, onTap: () -> Unit) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) .96f else 1f, animationSpec = tween(110), label = "coin-press")
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(228.dp).scale(scale).shadow(22.dp, CircleShape, ambientColor = Color(0x66D28B21), spotColor = Color(0x99F3B83F))
            .background(Brush.radialGradient(listOf(Color(0xFFFFF0B7), Color(0xFFFFD35F), Color(0xFFD58C23), Color(0xFF8F5115))), CircleShape)
            .border(1.dp, Color(0xFFFFE9A2), CircleShape)
            .clickable(enabled = !sending, onClick = {
                pressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                scope.launch { delay(100); pressed = false }
                onTap()
            }),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(194.dp).border(1.dp, Color(0x889C641E), CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Toll, contentDescription = "Tap golden coin", tint = Color(0xFF805016), modifier = Modifier.size(70.dp))
                Text("TAP", color = Color(0xFF70440F), fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            }
        }
    }
}

@Composable
private fun StatPanel(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Line), modifier = modifier) {
        Column(Modifier.padding(15.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            Text(value, color = Color(0xFFF4F0E7), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun WalletScreen(profile: TapProfile, transactions: List<LedgerItem>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Wallet", color = Color(0xFFF4F0E7), fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Server-confirmed account activity", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp, bottom = 20.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("AVAILABLE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("${profile.availableCoins} coins", color = GoldLight, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                Text("Lifetime earned  ${profile.lifetimeEarned}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Text("RECENT ACTIVITY", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            modifier = Modifier.padding(top = 25.dp, bottom = 11.dp))
        if (transactions.isEmpty()) Text("No transactions yet", color = Muted, fontSize = 14.sp)
        transactions.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }, color = Color(0xFFF4F0E7), fontSize = 14.sp)
                    Text(item.createdAt.take(10), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Text("${if (item.coinDelta > 0) "+" else ""}${item.coinDelta}",
                    color = if (item.coinDelta > 0) GoldLight else Color(0xFFFF9E83), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TasksScreen(tasks: List<TapTask>, onSubmit: (TapTask, String) -> Unit) {
    var selectedTask by remember { mutableStateOf<TapTask?>(null) }
    var proof by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Tasks", color = Color(0xFFF4F0E7), fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Rewards are credited after verification", color = Muted, fontSize = 13.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 16.dp))
        if (tasks.isEmpty()) Text("No active tasks", color = Muted, fontSize = 14.sp)
        tasks.forEach { task ->
            Surface(color = Panel, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Line),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.title, color = Color(0xFFF4F0E7), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        Text("+${task.rewardCoins}", color = GoldLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(task.description, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(task.category.uppercase().replace('_', ' '), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        when (task.status) {
                            "pending" -> Text("IN REVIEW", color = GoldLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            "approved" -> Text("COMPLETED", color = Color(0xFF86D1A0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            "rejected" -> Text("REJECTED", color = Color(0xFFFF9E83), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            else -> TextButton(onClick = { selectedTask = task; proof = "" }) {
                                Text("SUBMIT PROOF", color = GoldLight, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    selectedTask?.let { task ->
        AlertDialog(
            onDismissRequest = { selectedTask = null },
            containerColor = Panel,
            title = { Text("Submit task proof", color = Color(0xFFF4F0E7)) },
            text = {
                Column {
                    Text(task.title, color = GoldLight, fontSize = 13.sp)
                    OutlinedTextField(proof, { proof = it }, label = { Text("Proof or reference") }, modifier = Modifier.padding(top = 12.dp))
                    Text("Submissions are reviewed before any reward is issued.", color = Muted, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = proof.isNotBlank(), onClick = {
                    onSubmit(task, proof.trim())
                    selectedTask = null
                }) { Text("SEND", color = GoldLight) }
            },
            dismissButton = { TextButton(onClick = { selectedTask = null }) { Text("CANCEL", color = Muted) } }
        )
    }
}

@Composable
private fun LeaderboardScreen(items: List<RankItem>, period: String, onPeriodChange: (String) -> Unit) {
    val periods = listOf("daily", "weekly", "monthly", "lifetime")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Leaderboard", color = Color(0xFFF4F0E7), fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Verified tapping activity", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp, bottom = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            periods.forEach { option ->
                FilterChip(selected = period == option, onClick = { onPeriodChange(option) },
                    label = { Text(option.replaceFirstChar { it.uppercase() }, fontSize = 10.sp) })
            }
        }
        if (items.isEmpty()) Text("No rankings yet", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}".padStart(2, '0'), color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.username, color = Color(0xFFF4F0E7), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("${item.taps} taps", color = Muted, fontSize = 11.sp)
                }
                Text("${item.coins}", color = GoldLight, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProfileScreen(profile: TapProfile, busy: Boolean, onCopy: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Profile", color = Color(0xFFF4F0E7), fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Account details", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp, bottom = 20.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                ProfileLine("USERNAME", profile.username)
                ProfileLine("EMAIL", profile.email)
                ProfileLine("USER ID", profile.id)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("REFERRAL CODE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                        Text(profile.referralCode, color = GoldLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    }
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy referral code", tint = Gold)
                        Spacer(Modifier.width(5.dp))
                        Text("Copy", color = GoldLight)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp),
            border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(13.dp)) {
            Text(if (busy) "SIGNING OUT" else "SIGN OUT", color = Color(0xFFF4F0E7), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProfileLine(label: String, value: String) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        Text(value, color = Color(0xFFF4F0E7), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun EmptyScreen(title: String, description: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color(0xFFF4F0E7), fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(description, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 9.dp))
    }
}

@Composable
private fun OfflineBanner() {
    Surface(color = Color(0xFF34251B), modifier = Modifier.fillMaxWidth()) {
        Text("OFFLINE · Rewards are paused", color = GoldLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp))
    }
}