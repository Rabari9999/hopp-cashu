package space.sovereigncast.hopp.directory

/**
 * The main categories Hopp shows (device feedback 2026-10-19): Apple's top
 * level, which feeds name in `itunes:category` and the Podcast Index files
 * shows under. The index splits names into words ("Health", "Fitness") and
 * knows a few of its own ("Cryptocurrency"); every name below a main one —
 * Apple's subcategory or the index's word — counts for it.
 *
 * [index]: what the Podcast Index is asked for (`cat`, names joined by comma).
 */
enum class Category(val index: List<String>, members: List<String>) {
    Technology(listOf("Technology"), listOf("Cryptocurrency", "Tech News")),
    Education(listOf("Education"), listOf("Courses", "How To", "How-To", "Language", "Learning", "Language Learning", "Self-Improvement", "Self Improvement")),
    Business(listOf("Business"), listOf("Careers", "Entrepreneurship", "Investing", "Management", "Marketing", "Non-Profit", "Business News")),
    News(listOf("News"), listOf("Daily", "Daily News", "Entertainment", "Entertainment News", "Politics", "News Commentary", "Commentary")),
    Society(listOf("Society", "Culture"), listOf("Society & Culture", "Documentary", "Personal", "Journals", "Personal Journals", "Philosophy", "Places", "Travel", "Places & Travel", "Relationships")),
    Science(listOf("Science"), listOf("Astronomy", "Chemistry", "Earth", "Earth Sciences", "Life", "Life Sciences", "Mathematics", "Natural", "Natural Sciences", "Nature", "Physics", "Social", "Social Sciences", "Climate", "Weather")),
    History(listOf("History"), emptyList()),
    Health(listOf("Health", "Fitness"), listOf("Health & Fitness", "Alternative", "Alternative Health", "Medicine", "Mental", "Mental Health", "Nutrition", "Sexuality")),
    Sports(listOf("Sports"), listOf("Baseball", "Basketball", "Cricket", "Fantasy", "Fantasy Sports", "Football", "Golf", "Hockey", "Rugby", "Running", "Soccer", "Swimming", "Tennis", "Volleyball", "Wilderness", "Wrestling", "Sports News")),
    Comedy(listOf("Comedy"), listOf("Improv", "Stand-Up", "Comedy Interviews")),
    Arts(listOf("Arts"), listOf("Books", "Design", "Fashion", "Beauty", "Fashion & Beauty", "Food", "Performing", "Performing Arts", "Visual", "Visual Arts")),
    Music(listOf("Music"), listOf("Music Commentary", "Music History", "Music Interviews")),
    Leisure(listOf("Leisure"), listOf("Animation", "Manga", "Animation & Manga", "Automotive", "Aviation", "Crafts", "Games", "Hobbies", "Home", "Garden", "Home & Garden", "Video-Games", "Video Games", "Tabletop", "Role-Playing")),
    Kids(listOf("Kids", "Family"), listOf("Kids & Family", "Parenting", "Pets", "Animals", "Pets & Animals", "Stories", "Stories for Kids", "Education for Kids")),
    Fiction(listOf("Fiction"), listOf("Drama", "Science Fiction", "Comedy Fiction")),
    TrueCrime(listOf("True Crime"), emptyList()),
    Film(listOf("TV", "Film"), listOf("TV & Film", "After-Shows", "After Shows", "Reviews", "Film History", "Film Interviews", "Film Reviews", "TV Reviews")),
    Religion(listOf("Religion", "Spirituality"), listOf("Religion & Spirituality", "Buddhism", "Christianity", "Hinduism", "Islam", "Judaism")),
    Government(listOf("Government"), emptyList());

    /** The main names first: "Mental Health" is Health, not … anything that only shares a word. */
    internal val own: Set<String> = index.map(::plain).toSet()
    internal val below: Set<String> = members.map(::plain).toSet()

    companion object {
        /** The main categories [names] come to, in the order they first appear; unknown names are left out. */
        fun of(names: List<String>): List<Category> =
            names.mapNotNull(::of).distinct()

        fun of(name: String): Category? {
            val p = plain(name)
            if (p.isEmpty()) return null
            return entries.firstOrNull { p in it.own } ?: entries.firstOrNull { p in it.below }
        }

        fun byName(name: String): Category? = entries.firstOrNull { it.name == name }
    }
}

/** Letters only, lower case — top level, as the entries need it before any companion exists. */
private fun plain(s: String) = s.lowercase().filter { it.isLetter() }
