package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.directory.Category
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.category_technology
import space.sovereigncast.hopp.resources.category_education
import space.sovereigncast.hopp.resources.category_business
import space.sovereigncast.hopp.resources.category_news
import space.sovereigncast.hopp.resources.category_society
import space.sovereigncast.hopp.resources.category_science
import space.sovereigncast.hopp.resources.category_history
import space.sovereigncast.hopp.resources.category_health
import space.sovereigncast.hopp.resources.category_sports
import space.sovereigncast.hopp.resources.category_comedy
import space.sovereigncast.hopp.resources.category_arts
import space.sovereigncast.hopp.resources.category_music
import space.sovereigncast.hopp.resources.category_leisure
import space.sovereigncast.hopp.resources.category_kids
import space.sovereigncast.hopp.resources.category_fiction
import space.sovereigncast.hopp.resources.category_truecrime
import space.sovereigncast.hopp.resources.category_film
import space.sovereigncast.hopp.resources.category_religion
import space.sovereigncast.hopp.resources.category_government

/** A main category's name in the listener's language. */
@Composable
fun categoryLabel(c: Category): String = stringResource(c.label)

val Category.label: StringResource get() = when (this) {
    Category.Technology -> Res.string.category_technology
    Category.Education -> Res.string.category_education
    Category.Business -> Res.string.category_business
    Category.News -> Res.string.category_news
    Category.Society -> Res.string.category_society
    Category.Science -> Res.string.category_science
    Category.History -> Res.string.category_history
    Category.Health -> Res.string.category_health
    Category.Sports -> Res.string.category_sports
    Category.Comedy -> Res.string.category_comedy
    Category.Arts -> Res.string.category_arts
    Category.Music -> Res.string.category_music
    Category.Leisure -> Res.string.category_leisure
    Category.Kids -> Res.string.category_kids
    Category.Fiction -> Res.string.category_fiction
    Category.TrueCrime -> Res.string.category_truecrime
    Category.Film -> Res.string.category_film
    Category.Religion -> Res.string.category_religion
    Category.Government -> Res.string.category_government
}

/** Opens a category in Discover — from a show's page, which lives on another tab. */
val LocalOpenCategory = staticCompositionLocalOf<(Category) -> Unit> { {} }

/**
 * A show's main categories as small tags, at most [max] (device feedback
 * 2026-10-19); a tap opens the category in Discover. Nothing when the feed
 * names none Hopp knows.
 */
@Composable
fun CategoryTags(names: List<String>, modifier: Modifier = Modifier, max: Int = 2) {
    val categories = Category.of(names).take(max)
    if (categories.isEmpty()) return
    val open = LocalOpenCategory.current
    val colors = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        categories.forEach { c ->
            Text(
                categoryLabel(c),
                Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceVariant).clickable { open(c) }.padding(horizontal = 9.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}
