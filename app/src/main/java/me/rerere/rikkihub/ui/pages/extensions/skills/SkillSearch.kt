package me.rerere.rikkihub.ui.pages.extensions.skills

import me.rerere.rikkihub.data.files.SkillMetadata

internal const val SKILL_SEARCH_QUERY_MAX_CHARS = 256

/** Only metadata is searched; filtering never changes the source catalogue or reads skill files. */
internal fun filterSkills(skills: List<SkillMetadata>, query: String): List<SkillMetadata> {
    val term = query.trim()
    if (term.isEmpty()) return skills
    return skills.filter {
        it.name.contains(term, ignoreCase = true) || it.description.contains(term, ignoreCase = true)
    }
}
