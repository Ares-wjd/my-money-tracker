package com.mymoneytracker.app.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoneytracker.app.data.alerts.AlertSettingsRepository
import com.mymoneytracker.app.ui.common.AppFilterChip
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.common.NumberField
import com.mymoneytracker.app.ui.common.SectionCard
import com.mymoneytracker.app.ui.common.SegmentedPills
import com.mymoneytracker.app.ui.common.TopBarTitle
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.alerts.AlertRules
import com.mymoneytracker.core.alerts.AlertSettings
import com.mymoneytracker.core.alerts.CloseBasis
import com.mymoneytracker.core.alerts.CloseSetting
import com.mymoneytracker.core.alerts.CloseTimes
import com.mymoneytracker.core.market.MarketGroup
import com.mymoneytracker.core.portfolio.AllocationTarget
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 설정 › 알림 (docs/FEATURES.md 1-9, 디자인 ③). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlertSettingsScreen(
    viewModel: AlertSettingsViewModel,
    allocationTargets: List<AllocationTarget>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRecent by rememberSaveable { mutableStateOf(false) }

    // 권한 창·배터리 설정에서 돌아오면 다시 확인
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }

    fun requestBattery() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(direct) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun pickTime(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle("알림") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = settings
        if (s == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(highlighted = true) {
                SwitchRow("알림 받기", "설정은 계정에 저장 (다시 설치해도 유지) · 알림은 이 폰에서", s.enabled && readiness.ready) { on ->
                    if (on && !readiness.ready) {
                        if (!readiness.notifications) requestNotifications() else requestBattery()
                    }
                    viewModel.setEnabled(on)
                }
            }

            SectionCard(title = "필수 설정") {
                RequirementRow("알림 권한", readiness.notifications, "알림을 보여 주려면 필요해요") { requestNotifications() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RequirementRow("배터리 최적화 제외", readiness.battery, "안 하면 폰이 백그라운드 확인을 막아요") { requestBattery() }
                Text(
                    if (readiness.ready) "앱을 닫아 두어도 30분마다, 정해진 시각에는 바로 확인해요 (폰이 오래 쉬면 조금 늦을 수 있어요). 확인할 때 받은 시세는 PC 에도 저장돼요."
                    else "두 가지를 모두 허용해야 알림이 켜져요. 나중에 꺼지면 알림이 멈추고 홈에 안내가 떠요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (readiness.ready) muted else MaterialTheme.colorScheme.error,
                )
            }

            SectionCard(title = "장 마감 정리") {
                Text("내 종목만 · 합계와 많이 오른·내린 종목 3개씩 · 휴장일은 보내지 않아요 · 정한 시각에 보내요 (밤 모으기와 상관없이)", style = MaterialTheme.typography.bodySmall, color = muted)
                CloseBlock(MarketGroup.KR, s.krClose, ::pickTime) { c -> viewModel.update { it.copy(krClose = c) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CloseBlock(MarketGroup.US, s.usClose, ::pickTime) { c -> viewModel.update { it.copy(usClose = c) } }
            }

            SectionCard(title = "큰 등락") {
                SwitchRow("큰 등락 알림", "시장별 변동 일봉 기준 · 장이 열려 있을 때 확인", s.moveOn) { on -> viewModel.update { it.copy(moveOn = on) } }
                if (s.moveOn) {
                    PercentField("시장 합계 (국내·미국) ±%", s.moveMarket) { v -> viewModel.update { it.copy(moveMarket = v) } }
                    PercentField("종목 하나 ±%", s.moveItem) { v -> viewModel.update { it.copy(moveItem = v) } }
                    Text(
                        "같은 일봉에서 한 번만 알리고, 2배(±${MoneyFormat.decimal(s.moveMarket * 2, 1)}% · ±${MoneyFormat.decimal(s.moveItem * 2, 1)}%)를 넘으면 한 번 더. 평가금 1% 미만 종목은 빼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }

            SectionCard(title = "비중 허용 범위 이탈") {
                SwitchRow("비중 알림", "매일 ${AlertRules.ALLOCATION_TIME} 확인 · 범위 밖으로 나갈 때만 한 번", s.allocOn) { on -> viewModel.update { it.copy(allocOn = on) } }
                if (s.allocOn) {
                    if (allocationTargets.isEmpty()) {
                        Text("비중 탭에서 비중 설정을 만들면 여기서 고를 수 있어요.", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    allocationTargets.forEach { t ->
                        CheckRow(t.name, t.id !in s.allocOff) { checked ->
                            viewModel.update { it.copy(allocOff = if (checked) it.allocOff - t.id else it.allocOff + t.id) }
                        }
                    }
                }
            }

            SectionCard(title = "목표 마감 임박") {
                SwitchRow("목표 알림", "목적통장 목표 · 매일 ${AlertRules.GOAL_TIME} 확인", s.goalOn) { on -> viewModel.update { it.copy(goalOn = on) } }
                if (s.goalOn) {
                    Text("언제 알릴까요", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlertSettings.GOAL_DAY_OPTIONS.forEach { d ->
                            AppFilterChip(d in s.goalDays, if (d == 0) "당일" else "${d}일 전") {
                                viewModel.update { it.copy(goalDays = if (d in it.goalDays) it.goalDays - d else it.goalDays + d) }
                            }
                        }
                    }
                    CheckRow("반복 목표 예정일이 지났는데 소비 기록이 없으면 다음 날 한 번", s.goalOverdue) { c -> viewModel.update { it.copy(goalOverdue = c) } }
                }
            }

            SectionCard(title = "밤에는 모아서") {
                SwitchRow("밤 알림 모으기", "이 시간의 알림은 끝나는 시각에 한 번에 (장 마감 정리는 제외)", s.quietOn) { on -> viewModel.update { it.copy(quietOn = on) } }
                if (s.quietOn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickTime(s.quietFrom) { t -> viewModel.update { it.copy(quietFrom = t) } } }) { Text(AlertSettingsRepository.timeText(s.quietFrom)) }
                        Text("~")
                        OutlinedButton(onClick = { pickTime(s.quietTo) { t -> viewModel.update { it.copy(quietTo = t) } } }) { Text(AlertSettingsRepository.timeText(s.quietTo)) }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::sendTest, modifier = Modifier.weight(1f)) { Text("시험 알림 보내기") }
                Button(onClick = { showRecent = !showRecent }, modifier = Modifier.weight(1f)) { Text("최근 알림 ${recent.size}건") }
            }
            if (showRecent) {
                SectionCard(title = "최근 알림 (이 폰)") {
                    if (recent.isEmpty()) Text("아직 보낸 알림이 없어요.", style = MaterialTheme.typography.bodyMedium, color = muted)
                    recent.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(RECENT_TIME.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.width(76.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.bodyMedium)
                                if (r.body.isNotBlank()) Text(r.body, style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val RECENT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RequirementRow(label: String, ok: Boolean, why: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label + if (ok) "" else " · 필수", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!ok) Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ok) Text("허용됨", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        else Button(onClick = onFix) { Text("설정하기") }
    }
}

/** 기준값 입력 (0 초과 100 이하, 비우거나 잘못되면 저장하지 않음). */
@Composable
private fun PercentField(label: String, value: Double, onSave: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(MoneyFormat.decimal(value, 2)) }
    val parsed = text.toDoubleOrNull()
    val valid = parsed != null && parsed > 0 && parsed <= 100
    NumberField(
        label = label,
        value = text,
        onValueChange = {
            text = it
            it.toDoubleOrNull()?.takeIf { v -> v > 0 && v <= 100 }?.let(onSave)
        },
        isError = !valid,
        supporting = if (valid) null else "0보다 크고 100 이하로 넣어 주세요",
        preview = { "±${MoneyFormat.decimal(it, 2)}%" },
    )
}

/** 장 마감 정리 한 시장: 켜기, 마감 기준, 보낼 시각 (마감 + 10분 이후만). */
@Composable
private fun CloseBlock(
    group: MarketGroup,
    setting: CloseSetting,
    pickTime: (LocalTime, (LocalTime) -> Unit) -> Unit,
    onChange: (CloseSetting) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var clampedNote by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        CheckRow(group.label, setting.on) { onChange(setting.copy(on = it)) }
        if (!setting.on) return@Column
        Text("마감 기준", style = MaterialTheme.typography.bodySmall, color = muted)
        SegmentedPills(CloseBasis.entries, setting.basis, { CloseTimes.basisLabel(group, it).substringBefore(" (") + basisHint(group, it) }, { basis ->
            val time = CloseTimes.clamp(group, basis, setting.time)
            clampedNote = if (time != setting.time) "보낼 시각을 ${AlertSettingsRepository.timeText(time)} 로 맞췄어요" else null
            onChange(setting.copy(basis = basis, time = time))
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("보낼 시각", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                pickTime(setting.time) { picked ->
                    val time = CloseTimes.clamp(group, setting.basis, picked)
                    clampedNote = if (time != picked) "${AlertSettingsRepository.timeText(CloseTimes.earliest(group, setting.basis))} ~ ${AlertSettingsRepository.timeText(CloseTimes.LATEST)} 사이만 고를 수 있어 ${AlertSettingsRepository.timeText(time)} 로 맞췄어요" else null
                    onChange(setting.copy(time = time))
                }
            }) { Text(AlertSettingsRepository.timeText(setting.time)) }
        }
        val earliest = AlertSettingsRepository.timeText(CloseTimes.earliest(group, setting.basis))
        Text(
            "$earliest 이후로만 고를 수 있어요 (${CloseTimes.basisLabel(group, setting.basis)} + 10분" +
                (if (group == MarketGroup.US) ", 서머타임엔 한 시간 일찍 마감이라 늘 마감 뒤)" else ")"),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        clampedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

private fun basisHint(group: MarketGroup, basis: CloseBasis): String = when (group) {
    MarketGroup.KR -> if (basis == CloseBasis.REGULAR) " 15:30" else " 20:00"
    else -> if (basis == CloseBasis.REGULAR) " 06:00" else " 10:00"
}
