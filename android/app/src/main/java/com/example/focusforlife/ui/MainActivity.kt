package com.example.focusforlife.ui

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.focusforlife.accessibility.AppBlockerAccessibilityService
import com.example.focusforlife.admin.FocusAdminReceiver
import com.example.focusforlife.core.FocusLockManager
import com.example.focusforlife.core.FocusRules
import com.example.focusforlife.core.FocusTargets
import com.example.focusforlife.logging.FocusLogger
import com.example.focusforlife.services.FocusOverlayService
import com.example.focusforlife.services.FocusVpnService
import com.example.focusforlife.ui.theme.FocusForLifeTheme
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Compose dashboard that shows blocker state, remaining quota, and per-app stats.
 */
class MainActivity : ComponentActivity() {

    private var uiState by mutableStateOf(DashboardState())

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusLogger.init(this)
        FocusLogger.i("MainActivity created")
        refreshDashboard()
        setContent {
            FocusForLifeTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DashboardScreen(
                        state = uiState,
                        onRefresh = { refreshDashboard() },
                        onEnableAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onEnableVpn = { requestVpn() },
                        onStopVpn = { stopVpnService() },
                        onStartOverlay = { startOverlayService() },
                        onStopOverlay = { stopOverlayService() },
                        onEnableDeviceAdmin = { requestDeviceAdmin() },
                        onRequestExactAlarm = { requestExactAlarmPermission() },
                        onSetPin = { currentPin, newPin -> setPin(currentPin, newPin) },
                        onRequestDisable = { pin -> requestDisable(pin) },
                        onDisableNow = { disableNow() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FocusLogger.v("MainActivity resumed")
        refreshDashboard()
    }

    @Deprecated(
        message = "Legacy callback used only for VPN consent; replace with ActivityResult when stable."
    )
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun refreshDashboard() {
        FocusRules.ensureFreshDay(this)
        val blockStatus = FocusRules.blockStatus(this)
        val cooldownSeconds = FocusRules.cooldownRemainingSeconds(this)
        uiState = DashboardState(
            blockStatus = blockStatus,
            hardWindowRange = "${FocusRules.hardBlockStart().format(timeFormatter)} – ${FocusRules.hardBlockEnd().format(timeFormatter)}",
            remainingSeconds = FocusRules.remainingSeconds(this),
            isOverlayRunning = FocusOverlayService.isRunning(),
            sessionRemainingSeconds = FocusRules.sessionRemainingSeconds(this),
            nextLockSeconds = FocusRules.nextLockWindowSeconds(this),
            isAccessibilityEnabled = isAccessibilityEnabled(),
            isVpnRunning = FocusVpnService.isRunning(),
            isDeviceAdminEnabled = isDeviceAdminEnabled(),
            hasPin = FocusLockManager.hasPin(this),
            exactAlarmAllowed = isExactAlarmAllowed(),
            blockedApps = FocusTargets.blockedAppPackages,
            blockedDomains = FocusTargets.blockedDomains,
            perAppUsage = FocusRules.getPerAppUsageSeconds(this),
            cooldownRemainingSeconds = cooldownSeconds,
            isCooldownActive = blockStatus == FocusRules.BlockStatus.COOLDOWN && cooldownSeconds > 0,
            disableRemainingSeconds = FocusLockManager.disableRemainingSeconds(this),
            disableReady = FocusLockManager.isDisableReady(this)
        )
    }

    @Suppress("DEPRECATION")
    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            FocusLogger.i("Requesting VPN permission")
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        FocusRules.ensureFreshDay(this)
        startForegroundServiceCompat(Intent(this, FocusVpnService::class.java))
        FocusLogger.i("VPN service start requested")
        refreshDashboard()
    }

    private fun stopVpnService() {
        FocusLogger.i("VPN service stop requested")
        val intent = Intent(this, FocusVpnService::class.java).apply {
            action = FocusVpnService.ACTION_STOP
        }
        startService(intent)
        stopService(intent)
        refreshDashboard()
    }

    private fun startOverlayService() {
        if (!ensureOverlayPermission()) return
        startForegroundServiceCompat(Intent(this, FocusOverlayService::class.java))
        FocusLogger.i("Overlay service start requested")
        refreshDashboard()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, FocusOverlayService::class.java).apply {
            action = FocusOverlayService.ACTION_STOP
        }
        startService(intent)
        stopService(intent)
        FocusLogger.i("Overlay service stop requested")
        refreshDashboard()
    }

    private fun ensureOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            false
        } else {
            true
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val accessibilityEnabled =
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!accessibilityEnabled) return false
        val expectedComponent = ComponentName(this, AppBlockerAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        colonSplitter.forEach { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            if (component == expectedComponent) return true
        }
        return false
    }

    private fun isDeviceAdminEnabled(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, FocusAdminReceiver::class.java))
    }

    private fun requestDeviceAdmin() {
        val component = ComponentName(this, FocusAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable admin to make uninstall harder and add a disable delay."
            )
        }
        FocusLogger.i("Requesting device admin")
        startActivity(intent)
    }

    private fun isExactAlarmAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            toast("Exact alarm permission not required on this Android version.")
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        }
        FocusLogger.i("Requesting exact alarm permission")
        startActivity(intent)
    }

    private fun setPin(currentPin: String, newPin: String) {
        val hasPin = FocusLockManager.hasPin(this)
        if (newPin.length < 4) {
            toast("New PIN must be at least 4 digits.")
            return
        }
        if (hasPin && !FocusLockManager.verifyPin(this, currentPin)) {
            toast("Wrong current PIN.")
            return
        }
        FocusLogger.i("Updating PIN (hasExisting=$hasPin)")
        FocusLockManager.setPin(this, newPin)
        toast("PIN updated.")
        refreshDashboard()
    }

    private fun requestDisable(pin: String) {
        if (!FocusLockManager.hasPin(this)) {
            toast("Set a PIN first.")
            return
        }
        if (!FocusLockManager.verifyPin(this, pin)) {
            toast("Wrong PIN.")
            return
        }
        FocusLogger.i("Disable request accepted")
        FocusLockManager.requestDisable(this)
        toast("Disable scheduled. Wait 10 minutes.")
        refreshDashboard()
    }

    private fun disableNow() {
        if (!FocusLockManager.isDisableReady(this)) {
            toast("Disable window not reached yet.")
            return
        }
        val vpnIntent = Intent(this, FocusVpnService::class.java).apply {
            action = FocusVpnService.ACTION_STOP
        }
        val overlayIntent = Intent(this, FocusOverlayService::class.java).apply {
            action = FocusOverlayService.ACTION_STOP
        }
        startService(vpnIntent)
        startService(overlayIntent)
        stopService(vpnIntent)
        stopService(overlayIntent)
        FocusLockManager.clearDisableRequest(this)
        FocusLogger.i("Disable executed: VPN + overlay stopped")
        toast("VPN + overlay stopped. Disable accessibility manually if needed.")
        refreshDashboard()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }
}

data class DashboardState(
    val blockStatus: FocusRules.BlockStatus = FocusRules.BlockStatus.NONE,
    val hardWindowRange: String = "23:00 – 11:00",
    val remainingSeconds: Long = 3600,
    val isOverlayRunning: Boolean = false,
    val sessionRemainingSeconds: Long = 600,
    val nextLockSeconds: Long = 600,
    val isAccessibilityEnabled: Boolean = false,
    val isVpnRunning: Boolean = false,
    val isDeviceAdminEnabled: Boolean = false,
    val hasPin: Boolean = false,
    val exactAlarmAllowed: Boolean = true,
    val blockedApps: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
    val perAppUsage: Map<String, Long> = emptyMap(),
    val cooldownRemainingSeconds: Long = 0L,
    val isCooldownActive: Boolean = false,
    val disableRemainingSeconds: Long = 0L,
    val disableReady: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    onRefresh: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onEnableVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onEnableDeviceAdmin: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onSetPin: (String, String) -> Unit,
    onRequestDisable: (String) -> Unit,
    onDisableNow: () -> Unit
) {
    var currentPin by androidx.compose.runtime.remember { mutableStateOf("") }
    var newPin by androidx.compose.runtime.remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (isActive) {
            onRefresh()
            delay(1_000)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FocusForLife Control Center") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(state)
            ServiceCard(
                state = state,
                onEnableAccessibility = onEnableAccessibility,
                onEnableVpn = onEnableVpn,
                onStopVpn = onStopVpn,
                onStartOverlay = onStartOverlay,
                onStopOverlay = onStopOverlay,
                onRequestExactAlarm = onRequestExactAlarm
            )
            ProtectionCard(
                state = state,
                currentPin = currentPin,
                newPin = newPin,
                onCurrentPinChange = { currentPin = it },
                onNewPinChange = { newPin = it },
                onEnableDeviceAdmin = onEnableDeviceAdmin,
                onSetPin = { onSetPin(currentPin, newPin) },
                onRequestDisable = { onRequestDisable(currentPin) },
                onDisableNow = onDisableNow
            )
            BlockTargetsCard(state)
            UsageCard(state)
        }
    }
}

@Composable
private fun ProtectionCard(
    state: DashboardState,
    currentPin: String,
    newPin: String,
    onCurrentPinChange: (String) -> Unit,
    onNewPinChange: (String) -> Unit,
    onEnableDeviceAdmin: () -> Unit,
    onSetPin: () -> Unit,
    onRequestDisable: () -> Unit,
    onDisableNow: () -> Unit
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Protection", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (state.isDeviceAdminEnabled) "Device admin: ON" else "Device admin: OFF",
                color = if (state.isDeviceAdminEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
            if (!state.isDeviceAdminEnabled) {
                Button(onClick = onEnableDeviceAdmin) { Text("Enable Device Admin") }
            }
            OutlinedTextField(
                value = currentPin,
                onValueChange = onCurrentPinChange,
                label = { Text("Current PIN (for disable)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newPin,
                onValueChange = onNewPinChange,
                label = { Text("New PIN") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSetPin) {
                Text(if (state.hasPin) "Change PIN" else "Set PIN")
            }
            Button(onClick = onRequestDisable) {
                Text("Request Disable (10 min delay)")
            }
            if (state.disableRemainingSeconds > 0) {
                Text(
                    "Disable in ${formatDuration(state.disableRemainingSeconds)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onDisableNow, enabled = state.disableReady) {
                Text("Disable Now")
            }
            Text(
                "To uninstall, you must turn off device admin first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(state: DashboardState) {
    val (title, description, tone) = when (state.blockStatus) {
        FocusRules.BlockStatus.HARD_WINDOW -> Triple(
            "Hibernate Window Active",
            "Focus lockdown runs ${state.hardWindowRange}. All blocked apps + domains are disabled.",
            StatusTone.DANGER
        )
        FocusRules.BlockStatus.COOLDOWN -> Triple(
            "Hourly Limit Reached",
            "You used the 10-minute hourly quota. Resets in ${formatDuration(state.cooldownRemainingSeconds)}.",
            StatusTone.WARNING
        )
        FocusRules.BlockStatus.QUOTA -> Triple(
            "Daily Quota Exhausted",
            "The shared 1-hour allowance is gone. All blocked targets stay off until 23:00.",
            StatusTone.WARNING
        )
        FocusRules.BlockStatus.NONE -> Triple(
            "Within Safe Window",
            "You have ${formatDuration(state.remainingSeconds)} left. Hourly limit is 10 minutes.",
            StatusTone.SUCCESS
        )
    }
    ColoredCard(tone = tone) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(4.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "Next lock in ${formatDuration(state.nextLockSeconds)} (hourly left: ${formatDuration(state.sessionRemainingSeconds)}, daily left: ${formatDuration(state.remainingSeconds)}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "Hard block window: ${state.hardWindowRange}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ServiceCard(
    state: DashboardState,
    onEnableAccessibility: () -> Unit,
    onEnableVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onRequestExactAlarm: () -> Unit
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Guards", style = MaterialTheme.typography.titleMedium)
            ServiceStatusRow(
                label = "Accessibility Blocker",
                enabled = state.isAccessibilityEnabled,
                primaryActionLabel = "Enable",
                onPrimaryAction = onEnableAccessibility
            )
            ServiceStatusRow(
                label = "Network Blocker (VPN)",
                enabled = state.isVpnRunning,
                primaryActionLabel = "Enable",
                onPrimaryAction = onEnableVpn,
                secondaryActionLabel = "Stop",
                onSecondaryAction = onStopVpn
            )
            ServiceStatusRow(
                label = "Overlay Timer",
                enabled = state.isOverlayRunning,
                primaryActionLabel = "Show",
                onPrimaryAction = onStartOverlay,
                secondaryActionLabel = "Hide",
                onSecondaryAction = onStopOverlay
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val enableExactAlarmLabel = if (state.exactAlarmAllowed) null else "Enable"
                val enableExactAlarmAction = if (state.exactAlarmAllowed) null else onRequestExactAlarm
                ServiceStatusRow(
                    label = "Exact Alarm Precision",
                    enabled = state.exactAlarmAllowed,
                    primaryActionLabel = enableExactAlarmLabel,
                    onPrimaryAction = enableExactAlarmAction
                )
            }
        }
    }
}

@Composable
private fun ServiceStatusRow(
    label: String,
    enabled: Boolean,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Column {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(
            text = if (enabled) "ON" else "OFF",
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.size(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Button(onClick = onPrimaryAction) { Text(primaryActionLabel) }
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Button(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockTargetsCard(state: DashboardState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What’s Blocked", style = MaterialTheme.typography.titleMedium)
            Text("Apps", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.blockedApps.forEach {
                    TargetChip(it)
                }
            }
            Text("Domains", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.blockedDomains.forEach {
                    TargetChip(it)
                }
            }
        }
    }
}

@Composable
private fun TargetChip(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun UsageCard(state: DashboardState) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Usage Today", style = MaterialTheme.typography.titleMedium)
            if (state.perAppUsage.isEmpty()) {
                Text("No blocked apps opened yet. Keep going!", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.perAppUsage.entries.sortedByDescending { it.value }.forEach { (pkg, seconds) ->
                    Column {
                        Text(friendlySourceLabel(pkg), fontWeight = FontWeight.SemiBold)
                        Text(formatDuration(seconds), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun friendlySourceLabel(id: String): String {
    return if (id.startsWith(FocusRules.DOMAIN_PREFIX)) {
        "Web: ${id.removePrefix(FocusRules.DOMAIN_PREFIX)}"
    } else {
        id
    }
}

private enum class StatusTone { SUCCESS, WARNING, DANGER }

@Composable
private fun ColoredCard(tone: StatusTone, content: @Composable ColumnScope.() -> Unit) {
    val colors = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.DANGER -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = colors.first)) {
        CompositionLocalProvider(LocalContentColor provides colors.second) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                content = content
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return "%02dh %02dm %02ds".format(hrs, mins, secs)
}
