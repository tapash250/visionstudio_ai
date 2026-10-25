package com.visionstudio.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visionstudio.core.designsystem.component.AuroraBackground
import com.visionstudio.core.designsystem.component.GhostButton
import com.visionstudio.core.designsystem.component.GlassPanel
import com.visionstudio.core.designsystem.component.GlowTextField
import com.visionstudio.core.designsystem.component.OrbState
import com.visionstudio.core.designsystem.component.PrismButton
import com.visionstudio.core.designsystem.component.PrismSegmentedControl
import com.visionstudio.core.designsystem.component.SectionHeader
import com.visionstudio.core.designsystem.component.StatusOrb
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.rememberStudioHaptics
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.frostedGlass
import com.visionstudio.core.designsystem.modifier.glowingBorder
import com.visionstudio.core.designsystem.modifier.staggeredEntrance
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioError
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioSuccess
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.StudioTextTertiary
import com.visionstudio.core.designsystem.theme.StudioViolet
import com.visionstudio.core.designsystem.theme.StudioWarning
import com.visionstudio.core.designsystem.theme.VisionStudioTheme
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import kotlinx.coroutines.delay

@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state = state, onIntent = viewModel::onIntent, modifier = modifier)
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberStudioHaptics()
    val probe = state.probe
    LaunchedEffect(probe) {
        when (probe) {
            is ProbeUiState.Passed -> haptics.confirm()
            is ProbeUiState.Failed -> haptics.reject()
            else -> Unit
        }
    }
    LaunchedEffect(state.justSaved) {
        if (state.justSaved != null) {
            delay(SAVED_TOAST_MILLIS)
            onIntent(SettingsIntent.ConsumeSaved)
        }
    }

    AuroraBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Hero(Modifier.staggeredEntrance(0))

            AnimatedVisibility(
                visible = state.showRecoveryNotice,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                RecoveryBanner(onDismiss = { onIntent(SettingsIntent.DismissRecoveryNotice) })
            }

            PrismSegmentedControl(
                options = EngineSlot.entries,
                selected = state.selectedSlot,
                onSelect = { onIntent(SettingsIntent.SelectSlot(it)) },
                label = { slotLabel(it) },
                icon = { slotIcon(it) },
                height = 52.dp,
                modifier = Modifier.staggeredEntrance(1),
            )

            SlotStatusCard(state = state, modifier = Modifier.staggeredEntrance(2))

            AnimatedContent(
                targetState = state.selectedSlot,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val slideSpring = spring<androidx.compose.ui.unit.IntOffset>(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    (
                        slideInHorizontally(slideSpring) { width -> if (forward) width / 3 else -width / 3 } + fadeIn()
                        ).togetherWith(
                        slideOutHorizontally(slideSpring) { width -> if (forward) -width / 3 else width / 3 } + fadeOut(),
                    ).using(SizeTransform(clip = false))
                },
                label = "slotForm",
                modifier = Modifier.staggeredEntrance(3),
            ) { slot ->
                SlotForm(
                    draft = state.drafts.getValue(slot),
                    errors = state.fieldErrors,
                    enabled = !state.saving,
                    onIntent = onIntent,
                )
            }

            ProbeResultPanel(probe = probe)

            ActionRow(state = state, onIntent = onIntent, modifier = Modifier.staggeredEntrance(4))

            Spacer(Modifier.height(36.dp))
        }

        SavedToast(
            slot = state.justSaved,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun Hero(modifier: Modifier = Modifier) {
    val prismText = remember { Brush.linearGradient(listOf(StudioIndigoBright, StudioViolet, StudioCyan)) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.settings_eyebrow).uppercase(),
            style = VisionStudioTheme.typography.eyebrow,
            color = StudioCyan,
        )
        Text(
            text = stringResource(R.string.settings_title),
            style = VisionStudioTheme.typography.hero.copy(brush = prismText),
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = StudioTextSecondary,
        )
    }
}

@Composable
private fun SlotStatusCard(state: SettingsUiState, modifier: Modifier = Modifier) {
    val draft = state.draft
    val probe = state.probe
    val orb = when {
        probe is ProbeUiState.Testing || state.saving -> OrbState.Working
        probe is ProbeUiState.Passed -> OrbState.Success
        probe is ProbeUiState.Failed -> OrbState.Failure
        draft.persisted && !draft.dirty -> OrbState.Success
        else -> OrbState.Idle
    }
    val status = when {
        probe is ProbeUiState.Testing -> stringResource(R.string.status_testing)
        probe is ProbeUiState.Passed -> probe.latencyMillis?.let { stringResource(R.string.status_verified, it.toInt()) } ?: probe.headline
        probe is ProbeUiState.Failed -> stringResource(R.string.status_failed)
        draft.dirty -> stringResource(R.string.status_draft)
        draft.persisted -> stringResource(R.string.status_saved)
        else -> stringResource(R.string.status_unconfigured)
    }
    val statusColor = when (orb) {
        OrbState.Success -> StudioSuccess
        OrbState.Failure -> StudioError
        OrbState.Working -> StudioCyan
        OrbState.Idle -> if (draft.dirty) StudioWarning else StudioTextTertiary
    }

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        glowing = orb == OrbState.Success || orb == OrbState.Working,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusOrb(state = orb, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = draft.slot.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AnimatedContent(targetState = status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") {
                    Text(text = it, style = MaterialTheme.typography.labelMedium, color = statusColor)
                }
            }
            ProtocolTag(draft)
        }
    }
}

@Composable
private fun ProtocolTag(draft: SlotDraft) {
    val label = if (draft.protocol.isMcp) "MCP" else when (draft.restDialect) {
        RestDialect.OPENAI_COMPATIBLE -> "REST · OAI"
        RestDialect.FAL_QUEUE -> "REST · FAL"
        RestDialect.CUSTOM -> "REST · JSON"
    }
    Text(
        text = label,
        style = VisionStudioTheme.typography.monoSmall,
        color = StudioIndigoBright,
        modifier = Modifier
            .frostedGlass(shape = StudioShapes.Chip, border = SolidColor(StudioIndigoBright.copy(alpha = 0.35f)))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun SlotForm(
    draft: SlotDraft,
    errors: Map<SettingsField, String>,
    enabled: Boolean,
    onIntent: (SettingsIntent) -> Unit,
) {
    fun edit(field: SettingsField): (String) -> Unit = { onIntent(SettingsIntent.EditField(field, it)) }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionHeader(stringResource(R.string.section_protocol))
        PrismSegmentedControl(
            options = ProviderProtocol.entries,
            selected = draft.protocol,
            onSelect = { onIntent(SettingsIntent.SetProtocol(it)) },
            label = { if (it.isMcp) stringResource(R.string.protocol_mcp) else stringResource(R.string.protocol_rest) },
            icon = { if (it.isMcp) Icons.Rounded.Hub else Icons.Rounded.Cloud },
        )

        AnimatedContent(
            targetState = draft.protocol,
            transitionSpec = {
                (fadeIn() + slideInVertically { it / 8 }) togetherWith (fadeOut() + slideOutVertically { -it / 8 }) using
                    SizeTransform(clip = false)
            },
            label = "protocolFields",
        ) { protocol ->
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (protocol == ProviderProtocol.REST) {
                    SectionHeader(stringResource(R.string.section_dialect))
                    PrismSegmentedControl(
                        options = RestDialect.entries,
                        selected = draft.restDialect,
                        onSelect = { onIntent(SettingsIntent.SetDialect(it)) },
                        label = { dialectLabel(it) },
                        height = 44.dp,
                    )
                }
                SectionHeader(stringResource(R.string.section_connection))
                GlowTextField(
                    value = draft.endpointUrl,
                    onValueChange = edit(SettingsField.Endpoint),
                    label = stringResource(if (protocol.isMcp) R.string.field_sse_endpoint else R.string.field_endpoint),
                    placeholder = endpointHint(draft),
                    supportingText = errors[SettingsField.Endpoint] ?: if (protocol.isMcp) stringResource(R.string.support_mcp) else null,
                    isError = SettingsField.Endpoint in errors,
                    mono = true,
                    wrap = true,
                    leadingIcon = Icons.Rounded.Link,
                    keyboardType = KeyboardType.Uri,
                    enabled = enabled,
                )
                if (protocol.isMcp) {
                    GlowTextField(
                        value = draft.mcpToolName,
                        onValueChange = edit(SettingsField.Tool),
                        label = stringResource(R.string.field_tool),
                        placeholder = stringResource(R.string.hint_tool),
                        supportingText = errors[SettingsField.Tool],
                        isError = SettingsField.Tool in errors,
                        mono = true,
                        wrap = true,
                        leadingIcon = Icons.Rounded.Construction,
                        enabled = enabled,
                    )
                }
                GlowTextField(
                    value = draft.modelId,
                    onValueChange = edit(SettingsField.Model),
                    label = stringResource(if (protocol.isMcp) R.string.field_model_optional else R.string.field_model),
                    placeholder = modelHint(draft),
                    supportingText = errors[SettingsField.Model],
                    isError = SettingsField.Model in errors,
                    mono = true,
                    wrap = true,
                    leadingIcon = Icons.Rounded.Memory,
                    enabled = enabled,
                )
            }
        }

        SectionHeader(stringResource(R.string.section_auth))
        PrismSegmentedControl(
            options = AuthScheme.entries,
            selected = draft.authScheme,
            onSelect = { onIntent(SettingsIntent.SetAuthScheme(it)) },
            label = { authLabel(it) },
            height = 44.dp,
        )
        AnimatedVisibility(
            visible = draft.authScheme == AuthScheme.CUSTOM_HEADER,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            GlowTextField(
                value = draft.customAuthHeader,
                onValueChange = edit(SettingsField.Header),
                label = stringResource(R.string.field_header),
                placeholder = stringResource(R.string.hint_header),
                supportingText = errors[SettingsField.Header],
                isError = SettingsField.Header in errors,
                mono = true,
                leadingIcon = Icons.Rounded.Tag,
                enabled = enabled,
            )
        }
        AnimatedVisibility(
            visible = draft.authScheme != AuthScheme.NONE,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SectionHeader(stringResource(R.string.section_credentials)) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, tint = StudioSuccess, modifier = Modifier.size(16.dp))
                }
                GlowTextField(
                    value = draft.apiKeyInput,
                    onValueChange = edit(SettingsField.ApiKey),
                    label = stringResource(R.string.field_api_key),
                    placeholder = stringResource(if (draft.hasStoredKey) R.string.hint_api_key_stored else R.string.hint_api_key),
                    supportingText = errors[SettingsField.ApiKey] ?: stringResource(
                        if (draft.hasStoredKey) R.string.support_api_key_stored else R.string.support_api_key,
                    ),
                    isError = SettingsField.ApiKey in errors,
                    secret = true,
                    mono = true,
                    leadingIcon = Icons.Rounded.Key,
                    imeAction = ImeAction.Done,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun ProbeResultPanel(probe: ProbeUiState) {
    val result = probe as? ProbeUiState.Passed ?: probe as? ProbeUiState.Failed
    AnimatedVisibility(
        visible = result != null,
        enter = fadeIn() + expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy)) + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val passed = probe is ProbeUiState.Passed
        val (headline, details, latency) = when (probe) {
            is ProbeUiState.Passed -> Triple(probe.headline, probe.details, probe.latencyMillis)
            is ProbeUiState.Failed -> Triple(probe.headline, probe.details, probe.latencyMillis)
            else -> Triple("", emptyList(), null)
        }
        val accent = if (passed) StudioSuccess else StudioError
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glowingBorder(SolidColor(accent), StudioShapes.Card, width = 1.dp, glowRadius = 8.dp, intensity = { 0.6f })
                .frostedGlass(shape = StudioShapes.Card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = if (passed) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Text(headline, style = MaterialTheme.typography.titleMedium, color = accent, modifier = Modifier.weight(1f))
                latency?.let {
                    Text(
                        text = stringResource(R.string.latency_ms, it.toInt()),
                        style = VisionStudioTheme.typography.monoSmall,
                        color = StudioTextSecondary,
                    )
                }
            }
            details.forEach { line ->
                Text(text = "› $line", style = VisionStudioTheme.typography.monoSmall, color = StudioTextSecondary)
            }
        }
    }
}

@Composable
private fun ActionRow(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit, modifier: Modifier = Modifier) {
    val testing = state.probe is ProbeUiState.Testing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(
                text = stringResource(R.string.action_test),
                onClick = { onIntent(SettingsIntent.TestConnection) },
                enabled = !state.busy,
                icon = Icons.Rounded.NetworkCheck,
                modifier = Modifier.weight(0.4f),
            )
            PrismButton(
                text = stringResource(R.string.action_verify_save),
                onClick = { onIntent(SettingsIntent.VerifyAndSave) },
                loading = state.busy,
                loadingText = stringResource(if (state.saving) R.string.action_saving else R.string.action_verifying),
                icon = Icons.Rounded.Shield,
                modifier = Modifier.weight(0.6f),
            )
        }
        AnimatedVisibility(visible = state.probe is ProbeUiState.Failed && !testing, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = stringResource(R.string.action_save_anyway),
                style = MaterialTheme.typography.labelMedium,
                color = StudioTextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .tactileClickable(haptic = HapticPattern.Click, role = Role.Button) {
                        onIntent(SettingsIntent.SaveWithoutVerifying)
                    }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RecoveryBanner(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glowingBorder(SolidColor(StudioWarning), StudioShapes.Card, width = 1.dp, glowRadius = 6.dp, intensity = { 0.5f })
            .frostedGlass(shape = StudioShapes.Card)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = StudioWarning, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.titleSmall, color = StudioWarning)
            Text(stringResource(R.string.recovery_body), style = MaterialTheme.typography.bodySmall, color = StudioTextSecondary)
            Text(
                text = stringResource(R.string.action_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .tactileClickable(haptic = HapticPattern.Click, onClick = onDismiss),
            )
        }
    }
}

@Composable
private fun SavedToast(slot: EngineSlot?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = slot != null,
        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { it * 2 } + fadeIn(),
        exit = slideOutVertically { it * 2 } + fadeOut(),
        modifier = modifier,
    ) {
        val name = slot?.let { slotLabel(it) }.orEmpty()
        Row(
            modifier = Modifier
                .glowingBorder(SolidColor(StudioSuccess), StudioShapes.Chip, width = 1.dp, glowRadius = 10.dp, intensity = { 0.7f })
                .frostedGlass(shape = StudioShapes.Chip, fill = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = StudioSuccess, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.saved_toast, name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun slotLabel(slot: EngineSlot): String = stringResource(
    when (slot) {
        EngineSlot.TEXT_TO_IMAGE -> R.string.slot_t2i
        EngineSlot.IMAGE_EDIT -> R.string.slot_edit
        EngineSlot.VIDEO_STUDIO -> R.string.slot_video
    },
)

private fun slotIcon(slot: EngineSlot): ImageVector = when (slot) {
    EngineSlot.TEXT_TO_IMAGE -> Icons.Rounded.AutoAwesome
    EngineSlot.IMAGE_EDIT -> Icons.Rounded.AutoFixHigh
    EngineSlot.VIDEO_STUDIO -> Icons.Rounded.Movie
}

@Composable
private fun dialectLabel(dialect: RestDialect): String = stringResource(
    when (dialect) {
        RestDialect.OPENAI_COMPATIBLE -> R.string.dialect_openai
        RestDialect.FAL_QUEUE -> R.string.dialect_fal
        RestDialect.CUSTOM -> R.string.dialect_custom
    },
)

@Composable
private fun authLabel(scheme: AuthScheme): String = stringResource(
    when (scheme) {
        AuthScheme.BEARER -> R.string.auth_bearer
        AuthScheme.KEY -> R.string.auth_key
        AuthScheme.CUSTOM_HEADER -> R.string.auth_header
        AuthScheme.NONE -> R.string.auth_none
    },
)

@Composable
private fun endpointHint(draft: SlotDraft): String = stringResource(
    when {
        draft.protocol.isMcp -> R.string.hint_endpoint_mcp
        draft.restDialect == RestDialect.FAL_QUEUE -> R.string.hint_endpoint_fal
        draft.restDialect == RestDialect.CUSTOM -> R.string.hint_endpoint_custom
        else -> R.string.hint_endpoint_openai
    },
)

@Composable
private fun modelHint(draft: SlotDraft): String {
    val video = draft.slot == EngineSlot.VIDEO_STUDIO
    return stringResource(
        when {
            draft.restDialect == RestDialect.FAL_QUEUE && !draft.protocol.isMcp ->
                if (video) R.string.hint_model_video_fal else R.string.hint_model_fal
            video -> R.string.hint_model_video_openai
            else -> R.string.hint_model_openai
        },
    )
}

private const val SAVED_TOAST_MILLIS = 2_400L

@Preview(showBackground = true, backgroundColor = 0xFF0A0B0E, heightDp = 1500)
@Composable
private fun SettingsScreenPreview() {
    VisionStudioTheme {
        SettingsScreen(
            state = SettingsUiState(
                loaded = true,
                drafts = EngineSlot.entries.associateWith { SlotDraft(it) } + (
                    EngineSlot.TEXT_TO_IMAGE to SlotDraft(
                        slot = EngineSlot.TEXT_TO_IMAGE,
                        restDialect = RestDialect.FAL_QUEUE,
                        authScheme = AuthScheme.KEY,
                        endpointUrl = "https://queue.fal.run",
                        modelId = "fal-ai/flux/dev",
                        hasStoredKey = true,
                        persisted = true,
                    )
                    ),
                probes = EngineSlot.entries.associateWith<EngineSlot, ProbeUiState> { ProbeUiState.Idle } + (
                    EngineSlot.TEXT_TO_IMAGE to ProbeUiState.Passed(
                        headline = "Connected · key accepted",
                        details = listOf("Queue reachable for fal-ai/flux/dev"),
                        latencyMillis = 142,
                    )
                    ),
            ),
            onIntent = {},
        )
    }
}
