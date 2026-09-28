package com.sky22333.skyadb.ui.flyingmouse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ControlCamera
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sky22333.skyadb.lanmouse.LanMouseConnectionStatus
import com.sky22333.skyadb.ui.components.AppTopBar as TopAppBar
import com.sky22333.skyadb.ui.theme.AppDimens
import kotlinx.coroutines.withTimeoutOrNull


@Composable
fun FlyingMouseScreen(
    bottomPadding: Dp = 0.dp,
    onBackClick: () -> Unit,
    viewModel: FlyingMouseViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val connected = uiState.connectionStatus is LanMouseConnectionStatus.Connected
    val focusManager = LocalFocusManager.current
    var showDirectionalPad by rememberSaveable { mutableStateOf(false) }
    val dismissInputMode = {
        focusManager.clearFocus(force = true)
        viewModel.deactivateInputMode()
    }

    LaunchedEffect(connected) {
        if (!connected) showDirectionalPad = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("局域网飞鼠", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (connected) "已连接 ${uiState.host}" else "电视端服务 19870",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            navigationIcon = {
                if (!connected) {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = {
                if (connected) {
                    IconToggleButton(
                        checked = showDirectionalPad,
                        onCheckedChange = {
                            dismissInputMode()
                            showDirectionalPad = it
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ControlCamera,
                            contentDescription = if (showDirectionalPad) "隐藏方向键" else "显示方向键",
                            tint = if (showDirectionalPad) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    TextButton(
                        onClick = {
                            dismissInputMode()
                            showDirectionalPad = false
                            viewModel.disconnect()
                        },
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("断开")
                    }
                }
            },
            contentHeight = 44.dp,
        )

        if (connected) {
            RemoteContent(
                uiState = uiState,
                showDirectionalPad = showDirectionalPad,
                bottomPadding = bottomPadding,
                onToggleGyroscope = {
                    dismissInputMode()
                    viewModel.toggleGyroscope()
                },
                onRecalibrate = {
                    dismissInputMode()
                    viewModel.recalibrate()
                },
                onSensitivityChange = {
                    dismissInputMode()
                    viewModel.setSensitivity(it)
                },
                onMove = { dx, dy ->
                    dismissInputMode()
                    viewModel.moveByTouchpad(dx, dy)
                },
                onTap = {
                    dismissInputMode()
                    viewModel.tap()
                },
                onHoldStart = {
                    dismissInputMode()
                    viewModel.beginDrag()
                },
                onHoldMove = viewModel::dragBy,
                onHoldEnd = viewModel::endDrag,
                onScrollStart = { vertical, direction ->
                    dismissInputMode()
                    viewModel.beginScroll(vertical, direction)
                },
                onScroll = viewModel::scrollBy,
                onScrollEnd = viewModel::endScroll,
                onKey = {
                    dismissInputMode()
                    viewModel.sendKey(it)
                },
                onInputTextChange = viewModel::setInputText,
                onActivateInput = viewModel::activateInputMode,
                onDeactivateInput = viewModel::deactivateInputMode,
                onDeleteText = viewModel::deleteRemoteCharacter,
                onSendText = viewModel::sendInputText,
            )
        } else {
            ConnectionContent(
                uiState = uiState,
                bottomPadding = bottomPadding,
                onHostChange = viewModel::setHost,
                onConnect = viewModel::connect,
                onDeploy = viewModel::deployServer,
            )
        }
    }
}

@Composable
private fun ConnectionContent(
    uiState: FlyingMouseUiState,
    bottomPadding: Dp,
    onHostChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDeploy: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = AppDimens.ScreenPadding,
                top = AppDimens.ScreenPadding,
                end = AppDimens.ScreenPadding,
                bottom = AppDimens.ScreenPadding + bottomPadding,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            shape = RoundedCornerShape(AppDimens.CardRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(AppDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("连接电视", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = uiState.host,
                    onValueChange = onHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("电视 IP") },
                    supportingText = { Text("手机与电视需连接同一局域网") },
                    singleLine = true,
                )
                Text(
                    text = connectionText(uiState.connectionStatus),
                    color = connectionColor(uiState.connectionStatus),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onConnect,
                        enabled = uiState.connectionStatus !is LanMouseConnectionStatus.Connecting,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        if (uiState.connectionStatus is LanMouseConnectionStatus.Connecting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (uiState.connectionStatus is LanMouseConnectionStatus.Connecting) "连接中" else "连接")
                    }
                    OutlinedButton(
                        onClick = onDeploy,
                        enabled = !uiState.deploying,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        if (uiState.deploying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Android, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("一键部署")
                    }
                }
                if (uiState.deploymentMessage.isNotBlank()) {
                    Text(uiState.deploymentMessage, style = MaterialTheme.typography.bodySmall)
                }
                if (uiState.notice.isNotBlank()) {
                    Text(
                        text = uiState.notice,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteContent(
    uiState: FlyingMouseUiState,
    showDirectionalPad: Boolean,
    bottomPadding: Dp,
    onToggleGyroscope: () -> Unit,
    onRecalibrate: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onMove: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldMove: (Float, Float) -> Unit,
    onHoldEnd: () -> Unit,
    onScrollStart: (Boolean, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onScrollEnd: () -> Unit,
    onKey: (String) -> Unit,
    onInputTextChange: (String) -> Unit,
    onActivateInput: () -> Unit,
    onDeactivateInput: () -> Unit,
    onDeleteText: () -> Unit,
    onSendText: () -> Unit,
) {
    val splitPanelHeight = 220.dp
    val expandedTouchpadHeight = 446.dp
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppDimens.ScreenPadding,
            top = 6.dp,
            end = AppDimens.ScreenPadding,
            bottom = 4.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SensitivityControl(
                uiState = uiState,
                onToggle = onToggleGyroscope,
                onRecalibrate = onRecalibrate,
                onSensitivityChange = onSensitivityChange,
            )
        }
        if (showDirectionalPad) {
            item {
                DirectionPad(onKey = onKey)
            }
            item {
                Touchpad(
                    height = splitPanelHeight,
                    onMove = onMove,
                    onTap = onTap,
                    onHoldStart = onHoldStart,
                    onHoldMove = onHoldMove,
                    onHoldEnd = onHoldEnd,
                    onScrollStart = onScrollStart,
                    onScroll = onScroll,
                    onScrollEnd = onScrollEnd,
                )
            }
        } else {
            item {
                Touchpad(
                    height = expandedTouchpadHeight,
                    onMove = onMove,
                    onTap = onTap,
                    onHoldStart = onHoldStart,
                    onHoldMove = onHoldMove,
                    onHoldEnd = onHoldEnd,
                    onScrollStart = onScrollStart,
                    onScroll = onScroll,
                    onScrollEnd = onScrollEnd,
                )
            }
        }
        item {
            // Keep this confirmation key identical to the directional pad center key.
            // A cursor tap depends on the current pointer position and does not reliably
            // pause or resume focused TV playback controls.
            SystemKeyRow(onKey = onKey, onConfirm = { onKey(RemoteKey.Confirm.code) })
        }
        item {
            TextInputRow(
                text = uiState.inputText,
                onTextChange = onInputTextChange,
                onActivateInput = onActivateInput,
                onDeactivateInput = onDeactivateInput,
                onDelete = onDeleteText,
                onSend = onSendText,
            )
        }
    }
}

@Composable
private fun SensitivityControl(
    uiState: FlyingMouseUiState,
    onToggle: () -> Unit,
    onRecalibrate: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        !uiState.gyroscopeAvailable -> "当前手机没有陀螺仪"
                        uiState.calibrating -> "飞鼠校准中，请保持手机静止"
                        uiState.gyroscopeEnabled -> "飞鼠已开启"
                        else -> "飞鼠已暂停"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "灵敏度 ${uiState.sensitivity.toInt()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(
                onClick = onRecalibrate,
                enabled = uiState.gyroscopeEnabled,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新校准", modifier = Modifier.size(22.dp))
            }
            TextButton(
                onClick = onToggle,
                enabled = uiState.gyroscopeAvailable,
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(if (uiState.gyroscopeEnabled) "暂停" else "开启")
            }
        }
        Slider(
            value = uiState.sensitivity,
            onValueChange = onSensitivityChange,
            valueRange = 5f..30f,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

@Composable
private fun Touchpad(
    height: Dp,
    onMove: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldMove: (Float, Float) -> Unit,
    onHoldEnd: () -> Unit,
    onScrollStart: (Boolean, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onScrollEnd: () -> Unit,
) {
    val shape = RoundedCornerShape(AppDimens.CardRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            val holdWindow = viewConfiguration.longPressTimeoutMillis

                            // Three outcomes, decided once and then locked:
                            //   slide -> every delta is forwarded immediately (pointer glued to the finger)
                            //   hold  -> finger stays within slop for the whole long-press window
                            //   tap   -> released before either the slop or the long-press window is met
                            var travel = 0f
                            var lifted = false
                            var holding = false

                            withTimeoutOrNull(holdWindow) {
                                while (true) {
                                    val next = awaitPointerEvent(PointerEventPass.Main)
                                    val change = next.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) {
                                        lifted = true
                                        return@withTimeoutOrNull
                                    }
                                    val delta = change.positionChange()
                                    if (delta.getDistance() > 0f) {
                                        onMove(delta.x, delta.y)
                                        travel += delta.getDistance()
                                        change.consume()
                                    }
                                    if (travel > slop) {
                                        return@withTimeoutOrNull
                                    }
                                }
                            }
                            if (!lifted && travel <= slop) {
                                holding = true
                                onHoldStart()
                            }

                            while (!lifted) {
                                val next = awaitPointerEvent(PointerEventPass.Main)
                                val change = next.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    lifted = true
                                    break
                                }
                                val delta = change.positionChange()
                                if (delta.getDistance() > 0f) {
                                    if (holding) {
                                        onHoldMove(delta.x, delta.y)
                                    } else {
                                        onMove(delta.x, delta.y)
                                    }
                                    change.consume()
                                }
                            }

                            when {
                                holding -> onHoldEnd()
                                travel <= slop -> onTap()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("触控板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "拖动 · 点击 · 长按",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            ScrollZone(
                vertical = true,
                modifier = Modifier.width(44.dp).fillMaxSize(),
                onScrollStart = onScrollStart,
                onScroll = onScroll,
                onScrollEnd = onScrollEnd,
            )
        }
        Row(modifier = Modifier.height(44.dp)) {
            ScrollZone(
                vertical = false,
                modifier = Modifier.weight(1f).fillMaxSize(),
                onScrollStart = onScrollStart,
                onScroll = onScroll,
                onScrollEnd = onScrollEnd,
            )
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Swipe,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScrollZone(
    vertical: Boolean,
    modifier: Modifier,
    onScrollStart: (Boolean, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onScrollEnd: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(vertical) {
                val accumulator = ScrollDeltaAccumulator()
                var remoteTouchActive = false
                detectDragGestures(
                    onDragStart = {
                        accumulator.reset()
                        remoteTouchActive = false
                    },
                    onDragEnd = {
                        if (remoteTouchActive) onScrollEnd()
                        remoteTouchActive = false
                    },
                    onDragCancel = {
                        if (remoteTouchActive) onScrollEnd()
                        remoteTouchActive = false
                    },
                ) { change, amount ->
                    change.consume()
                    val primaryDelta = if (vertical) amount.y else amount.x
                    if (primaryDelta != 0f) {
                        if (!remoteTouchActive) {
                            onScrollStart(vertical, primaryDelta)
                            remoteTouchActive = true
                        }
                        if (vertical) {
                            onScroll(0f, accumulator.addVertical(amount.y))
                        } else {
                            onScroll(accumulator.addHorizontal(amount.x), 0f)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (vertical) "↑\n↓" else "←    →",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DirectionPad(onKey: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        shape = RoundedCornerShape(AppDimens.CardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DirectionKeyButton(
                icon = Icons.Outlined.KeyboardArrowUp,
                description = "上",
                width = 110.dp,
                height = 54.dp,
            ) { onKey("KEYCODE_DPAD_UP") }
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DirectionKeyButton(
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    description = "左",
                    width = 90.dp,
                    height = 68.dp,
                ) { onKey("KEYCODE_DPAD_LEFT") }
                DirectionKeyButton(
                    icon = Icons.Outlined.CheckCircle,
                    description = "确认",
                    width = 110.dp,
                    height = 68.dp,
                    selected = true,
                ) { onKey(RemoteKey.Confirm.code) }
                DirectionKeyButton(
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    description = "右",
                    width = 90.dp,
                    height = 68.dp,
                ) { onKey("KEYCODE_DPAD_RIGHT") }
            }
            DirectionKeyButton(
                icon = Icons.Outlined.KeyboardArrowDown,
                description = "下",
                width = 110.dp,
                height = 54.dp,
            ) { onKey("KEYCODE_DPAD_DOWN") }
        }
    }
}

@Composable
private fun DirectionKeyButton(
    icon: ImageVector,
    description: String,
    width: Dp,
    height: Dp,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(width)
            .height(height)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (selected) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            )
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(if (selected) 38.dp else 34.dp),
            )
        }
    }
}

@Composable
private fun SystemKeyRow(onKey: (String) -> Unit, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomKey(Icons.AutoMirrored.Outlined.ArrowBack, "返回") { onKey("KEYCODE_BACK") }
        BottomKey(Icons.Outlined.Home, "主页") { onKey("KEYCODE_HOME") }
        BottomKey(Icons.Outlined.CheckCircle, "确认", onConfirm)
        BottomKey(Icons.AutoMirrored.Outlined.VolumeDown, "音量减") { onKey("KEYCODE_VOLUME_DOWN") }
        BottomKey(Icons.AutoMirrored.Outlined.VolumeUp, "音量加") { onKey("KEYCODE_VOLUME_UP") }
    }
}

@Composable
private fun BottomKey(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun TextInputRow(
    text: String,
    onTextChange: (String) -> Unit,
    onActivateInput: () -> Unit,
    onDeactivateInput: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    onActivateInput()
                } else {
                    onDeactivateInput()
                }
            },
        placeholder = { Text("发送文本") },
        leadingIcon = {
            Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(22.dp))
        },
        trailingIcon = {
            Row {
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Backspace,
                        contentDescription = "删除电视上的字符",
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = onSend, enabled = text.isNotBlank(), modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送", modifier = Modifier.size(22.dp))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
    )
}

@Composable
private fun connectionText(status: LanMouseConnectionStatus): String = when (status) {
    LanMouseConnectionStatus.Disconnected -> "未连接电视端服务"
    LanMouseConnectionStatus.Connecting -> "正在连接电视端服务"
    is LanMouseConnectionStatus.Connected -> "已连接 ${status.endpoint}"
    is LanMouseConnectionStatus.Failed -> "连接失败：${status.message}"
}

@Composable
private fun connectionColor(status: LanMouseConnectionStatus) = when (status) {
    is LanMouseConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
    is LanMouseConnectionStatus.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
