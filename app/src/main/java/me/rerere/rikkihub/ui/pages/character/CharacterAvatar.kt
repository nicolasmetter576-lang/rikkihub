package me.rerere.rikkihub.ui.pages.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.data.repository.CharacterRepository
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

/** 角色的头像：卡面图片，没有就用名字的首字。图片文件在角色自己的目录里（`avatarPath` 相对它）。 */
@Composable
fun CharacterAvatar(
    id: Uuid,
    name: String,
    avatarPath: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val repository: CharacterRepository = koinInject()
    val file: File? = remember(id, avatarPath) {
        avatarPath.takeIf { it.isNotEmpty() }?.let { File(repository.directory(id), it) }?.takeIf { it.isFile }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(model = file, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun CharacterAvatar(summary: CharacterSummary, modifier: Modifier = Modifier, size: Dp = 48.dp) =
    CharacterAvatar(summary.id, summary.name, summary.avatarPath, modifier, size)
