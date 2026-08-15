package com.hamer.dockshortcut.drawer

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.hamer.dockshortcut.R
import com.hamer.dockshortcut.ui.theme.PicoDockShortcutTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePicker(onDismiss: () -> Unit) {
    val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = Color.Transparent,
        containerColor = colorResource(R.color.content_bg),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        LanguagePickerContent(
            currentLocale = currentLocale,
            onLanguageSelected = { tag ->
                val appLocale: LocaleListCompat = if (tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
                onDismiss()
            }
        )
    }
}

@Composable
fun LanguagePickerContent(
    currentLocale: String,
    onLanguageSelected: (String) -> Unit
) {
    val supportedLocales = listOf(
        "Auto" to "",
        "English" to "en",
        "English (UK)" to "en-GB",
        "简体中文" to "zh-CN",
        "繁體中文 (台灣)" to "zh-TW",
        "繁體中文 (香港)" to "zh-HK",
        "Deutsch" to "de",
        "Français" to "fr",
        "Español" to "es",
        "Español (US)" to "es-US",
        "Italiano" to "it",
        "日本語" to "ja",
        "한국어" to "ko",
        "Русский" to "ru",
        "ไทย" to "th",
        "Türkçe" to "tr",
        "Čeština" to "cs",
        "Dansk" to "da",
        "Nederlands" to "nl",
        "Suomi" to "fi",
        "Eλληνικά" to "el",
        "Bahasa Melayu" to "ms",
        "Norsk Bokmål" to "nb",
        "Polski" to "pl",
        "Português (Brasil)" to "pt-BR",
        "Português (Portugal)" to "pt-PT",
        "Română" to "ro",
        "Svenska" to "sv"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = stringResource(R.string.select_language),
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(supportedLocales) { (name, tag) ->
                val isSelected = (tag == "" && currentLocale == "") ||
                        (tag != "" && currentLocale.startsWith(tag))

                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isHovered) colorResource(R.color.card_bg) else Color.Transparent
                        )
                        .hoverable(interactionSource)
                        .clickable { onLanguageSelected(tag) }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LanguagePickerPreview() {
    PicoDockShortcutTheme {
        Surface(color = colorResource(R.color.content_bg)) {
            LanguagePickerContent(
                currentLocale = "en",
                onLanguageSelected = {}
            )
        }
    }
}
