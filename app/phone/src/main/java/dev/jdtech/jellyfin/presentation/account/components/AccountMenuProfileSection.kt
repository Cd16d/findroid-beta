package dev.jdtech.jellyfin.presentation.account.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.components.UserAvatar
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

@Composable
fun AccountMenuProfileSection(
    userName: String,
    userImageUrl: Any?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UserAvatar(
            name = userName,
            imageUrl = userImageUrl,
            size = 96.dp,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Greeting Name
        Text(
            text = stringResource(CoreR.string.hi_user, userName),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Preview
@Composable
fun AccountMenuProfileSectionPreview() {
    FindroidTheme {
        Surface {
            AccountMenuProfileSection(
                userName = dummyUser.name,
                userImageUrl = null,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
