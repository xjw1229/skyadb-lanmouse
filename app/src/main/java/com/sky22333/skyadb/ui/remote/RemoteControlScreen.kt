package com.sky22333.skyadb.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import com.sky22333.skyadb.ui.components.AppTopBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sky22333.skyadb.model.OperationStatus
import com.sky22333.skyadb.ui.components.SectionHeader
import com.sky22333.skyadb.ui.theme.AdbManagerTheme
import com.sky22333.skyadb.ui.theme.AppDimens

private val RemoteButtonSize = 72.dp
private val RemoteCenterButtonSize = 82.dp

@Composable
fun RemoteControlScreen(
    bottomPadding: Dp = 0.dp,
    onBackClick: () -> Unit,
    viewModel: RemoteControlViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    RemoteControlContent(
        bottomPadding = bottomPadding,
        uiState = uiState,
        onBackClick = onBackClick,
        onKeyClick = viewModel::sendKey,
    )
}

@Composable
private fun RemoteControlContent(
    bottomPadding: Dp = 0.dp,
    uiState: RemoteControlUiState,
    onBackClick: () -> Unit,
    onKeyClick: (RemoteKey) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("虚拟遥控器")
                    Text(
                        "发送物理按键到目标设备",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppDimens.ScreenPadding,
                top = AppDimens.ScreenPadding,
                end = AppDimens.ScreenPadding,
                bottom = AppDimens.ScreenPadding + bottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(AppDimens.SectionGap),
        ) {
            item { RemoteStatus(status = uiState.status) }

            item { SectionHeader(title = "常用操作") }

            item {
                KeyGrid(
                    keys = listOf(
                        KeyAction(RemoteKey.Back, Icons.AutoMirrored.Outlined.ArrowBack),
                        KeyAction(RemoteKey.Home, Icons.Outlined.Home),
                        KeyAction(RemoteKey.Menu, Icons.Outlined.Menu),
                    ),
                    onKeyClick = onKeyClick,
                )
            }

            item {
                SectionHeader(
                    title = "方向键",
                    description = "适合电视、盒子和无触控设备",
                )
            }

            item { DpadCard(onKeyClick = onKeyClick) }

            item { SectionHeader(title = "音量控制") }

            item {
                KeyGrid(
                    keys = listOf(
                        KeyAction(RemoteKey.VolumeDown, Icons.AutoMirrored.Outlined.VolumeDown),
                        KeyAction(RemoteKey.VolumeUp, Icons.AutoMirrored.Outlined.VolumeUp),
                        KeyAction(RemoteKey.Mute, Icons.AutoMirrored.Outlined.VolumeOff),
                    ),
                    onKeyClick = onKeyClick,
                )
            }

            item { SectionHeader(title = "媒体控制") }

            item {
                KeyGrid(
                    keys = listOf(
                        KeyAction(RemoteKey.Previous, Icons.AutoMirrored.Outlined.KeyboardArrowLeft),
                        KeyAction(RemoteKey.PlayPause, Icons.Outlined.PlayArrow),
                        KeyAction(RemoteKey.Next, Icons.AutoMirrored.Outlined.KeyboardArrowRight),
                    ),
                    onKeyClick = onKeyClick,
                )
            }

            item { SectionHeader(title = "电源") }

            item {
                KeyGrid(
                    keys = listOf(
                        KeyAction(RemoteKey.Wakeup, Icons.Outlined.PowerSettingsNew),
                        KeyAction(RemoteKey.Sleep, Icons.Outlined.PowerSettingsNew),
                        KeyAction(RemoteKey.Power, Icons.Outlined.PowerSettingsNew),
                    ),
                    onKeyClick = onKeyClick,
                )
            }
        }
    }
}

@Composable
private fun DpadCard(onKeyClick: (RemoteKey) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppDimens.CardRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppDimens.CardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteIconButton(
                key = RemoteKey.Up,
                icon = Icons.Outlined.KeyboardArrowUp,
                onKeyClick = onKeyClick,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteIconButton(
                    key = RemoteKey.Left,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    onKeyClick = onKeyClick,
                )

                Button(
                    onClick = { onKeyClick(RemoteKey.Center) },
                    modifier = Modifier.size(RemoteCenterButtonSize),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text(
                        text = RemoteKey.Center.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                RemoteIconButton(
                    key = RemoteKey.Right,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    onKeyClick = onKeyClick,
                )
            }

            RemoteIconButton(
                key = RemoteKey.Down,
                icon = Icons.Outlined.KeyboardArrowDown,
                onKeyClick = onKeyClick,
            )
        }
    }
}

@Composable
private fun KeyGrid(
    keys: List<KeyAction>,
    onKeyClick: (RemoteKey) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { action ->
                    OutlinedButton(
                        onClick = { onKeyClick(action.key) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.key.label,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(action.key.label)
                    }
                }

                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RemoteIconButton(
    key: RemoteKey,
    icon: ImageVector,
    onKeyClick: (RemoteKey) -> Unit,
) {
    OutlinedButton(
        onClick = { onKeyClick(key) },
        modifier = Modifier.size(RemoteButtonSize),
        shape = RoundedCornerShape(24.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = key.label,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun RemoteStatus(status: OperationStatus) {
    when (status) {
        OperationStatus.Idle -> Unit

        is OperationStatus.Running -> Text(
            text = status.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is OperationStatus.Success -> Text(
            text = status.text,
            color = MaterialTheme.colorScheme.primary,
        )

        is OperationStatus.Failed -> Text(
            text = "${status.text}：${status.suggestion}",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private data class KeyAction(
    val key: RemoteKey,
    val icon: ImageVector,
)

@Preview(name = "虚拟遥控器", showBackground = true, widthDp = 390)
@Composable
private fun RemoteControlContentPreview() {
    AdbManagerTheme(dynamicColor = false) {
        RemoteControlContent(
            uiState = RemoteControlUiState(),
            onBackClick = {},
            onKeyClick = {},
        )
    }
}